package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunRuntimeOutputQuery;
import com.example.agentweb.app.chatrun.RecoveredRuntimeOutput;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从 SQLite append-only Run Event 中有界恢复公共 Runtime 输出。
 *
 * <p>读取 {@code agent_chunk}、{@code tool_started}、{@code tool_finished} 事件，
 * 按 seq 交错重建为 stream-json NDJSON（与 Chat 路径的 {@code StreamChunkHandler}
 * 累积格式一致），使前端 {@code parseStreamJson()} 能解析出 text / tool segments。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteChatRunRuntimeOutputQuery
        implements ChatRunRuntimeOutputQuery {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final long maximumOutputBytes;

    public SqliteChatRunRuntimeOutputQuery(
            JdbcTemplate jdbc,
            @Value("${agent.runtime.recovery-max-output-bytes:10485760}")
                    long maximumOutputBytes) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (maximumOutputBytes < 1L) {
            throw new IllegalArgumentException(
                    "runtime recovery output limit must be positive");
        }
        this.maximumOutputBytes = maximumOutputBytes;
    }

    @Override
    public RecoveredRuntimeOutput load(
            ChatRunId runId, RuntimeHandle handle) {
        requireMatchingHandle(runId, handle);
        List<EventRow> rows = jdbc.query(
                "SELECT seq, event_type, payload, payload_size "
                        + "FROM chat_run_event "
                        + "WHERE run_id=? "
                        + "AND event_type IN ('agent_chunk','tool_started','tool_finished') "
                        + "ORDER BY seq ASC",
                (resultSet, rowNumber) -> new EventRow(
                        resultSet.getLong("seq"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload"),
                        resultSet.getInt("payload_size")),
                runId.getValue());
        StringBuilder output = new StringBuilder();
        long outputBytes = 0L;
        long previousSeq = 0L;
        long previousRuntimeSequence = -1L;
        for (EventRow row : rows) {
            if (row.seq <= previousSeq) {
                return RecoveredRuntimeOutput.incomplete();
            }
            ParsedEvent parsed = toStreamJson(row);
            if (parsed == null
                    || parsed.runtimeSequence <= previousRuntimeSequence) {
                return RecoveredRuntimeOutput.incomplete();
            }
            byte[] encoded = parsed.json.getBytes(StandardCharsets.UTF_8);
            long separatorBytes = output.length() == 0 ? 0L : 1L;
            if (encoded.length > maximumOutputBytes - outputBytes - separatorBytes) {
                return RecoveredRuntimeOutput.incomplete();
            }
            if (separatorBytes > 0L) {
                output.append('\n');
            }
            output.append(parsed.json);
            outputBytes += separatorBytes + encoded.length;
            previousSeq = row.seq;
            previousRuntimeSequence = parsed.runtimeSequence;
        }
        return RecoveredRuntimeOutput.complete(output.toString());
    }

    private ParsedEvent toStreamJson(EventRow row) {
        try {
            if (row.payload == null) return null;
            if (row.payloadSize
                    != row.payload.getBytes(StandardCharsets.UTF_8).length) {
                return null;
            }
            JsonNode root = MAPPER.readTree(row.payload);
            JsonNode seqNode = root.get("runtimeSequence");
            if (seqNode == null || !seqNode.canConvertToLong()
                    || seqNode.longValue() < 0L) {
                return null;
            }
            long runtimeSequence = seqNode.longValue();
            String json;
            switch (row.eventType) {
                case "agent_chunk":
                    json = agentChunkStreamJson(root);
                    break;
                case "tool_started":
                    json = toolStartedStreamJson(root);
                    break;
                case "tool_finished":
                    json = toolFinishedStreamJson(root);
                    break;
                default:
                    return null;
            }
            if (json == null) return null;
            return new ParsedEvent(runtimeSequence, json);
        } catch (RuntimeException | java.io.IOException failure) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String agentChunkStreamJson(JsonNode root) {
        JsonNode content = root.get("content");
        if (content == null || !content.isTextual()) return null;
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "text_delta");
        delta.put("text", content.textValue());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "content_block_delta");
        event.put("index", 0);
        event.put("delta", delta);
        return streamEventJson(event);
    }

    @SuppressWarnings("unchecked")
    private String toolStartedStreamJson(JsonNode root) {
        JsonNode tool = root.get("tool");
        JsonNode callId = root.get("callId");
        if (tool == null || callId == null) return null;
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", callId.textValue());
        block.put("name", tool.textValue());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "content_block_start");
        event.put("index", 0);
        event.put("content_block", block);
        return streamEventJson(event);
    }

    @SuppressWarnings("unchecked")
    private String toolFinishedStreamJson(JsonNode root) {
        JsonNode callId = root.get("callId");
        JsonNode status = root.get("status");
        JsonNode outputSummary = root.get("outputSummary");
        if (callId == null) return null;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "tool_result");
        content.put("tool_use_id", callId.textValue());
        content.put("content", status != null ? status.textValue() : "");
        if ("FAILED".equals(status != null ? status.textValue() : null)) {
            content.put("is_error", true);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", List.of(content));
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "user");
        wrapper.put("message", message);
        if (outputSummary != null && outputSummary.isTextual()) {
            wrapper.put("tool_use_result", outputSummary.textValue());
        }
        try {
            return MAPPER.writeValueAsString(wrapper);
        } catch (java.io.IOException failure) {
            return null;
        }
    }

    private String streamEventJson(Map<String, Object> event) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "stream_event");
        wrapper.put("event", event);
        try {
            return MAPPER.writeValueAsString(wrapper);
        } catch (java.io.IOException failure) {
            return null;
        }
    }

    private void requireMatchingHandle(
            ChatRunId runId, RuntimeHandle handle) {
        if (runId == null || handle == null
                || !runId.getValue().equals(handle.getExecutionId())) {
            throw new IllegalArgumentException(
                    "runtime recovery handle must match chat run");
        }
    }

    private static final class EventRow {
        private final long seq;
        private final String eventType;
        private final String payload;
        private final int payloadSize;

        private EventRow(
                long seq, String eventType, String payload, int payloadSize) {
            this.seq = seq;
            this.eventType = eventType;
            this.payload = payload;
            this.payloadSize = payloadSize;
        }
    }

    private static final class ParsedEvent {
        private final long runtimeSequence;
        private final String json;

        private ParsedEvent(long runtimeSequence, String json) {
            this.runtimeSequence = runtimeSequence;
            this.json = json;
        }
    }
}