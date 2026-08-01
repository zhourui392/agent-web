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
import java.util.List;
import java.util.Objects;

/**
 * 从 SQLite append-only Run Event 中有界恢复公共 Runtime 输出。
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
        List<OutputRow> rows = jdbc.query(
                "SELECT seq, payload, payload_size "
                        + "FROM chat_run_event "
                        + "WHERE run_id=? AND event_type='agent_chunk' "
                        + "ORDER BY seq ASC",
                (resultSet, rowNumber) -> new OutputRow(
                        resultSet.getLong("seq"),
                        resultSet.getString("payload"),
                        resultSet.getInt("payload_size")),
                runId.getValue());
        StringBuilder output = new StringBuilder();
        long outputBytes = 0L;
        long previousEventSequence = 0L;
        long previousRuntimeSequence = -1L;
        for (OutputRow row : rows) {
            ParsedOutput parsed = parse(row);
            if (row.eventSequence <= previousEventSequence
                    || parsed == null
                    || parsed.runtimeSequence <= previousRuntimeSequence) {
                return RecoveredRuntimeOutput.incomplete();
            }
            byte[] encoded = parsed.payload.getBytes(StandardCharsets.UTF_8);
            long separatorBytes = output.length() == 0 ? 0L : 1L;
            if (encoded.length > maximumOutputBytes - outputBytes - separatorBytes) {
                return RecoveredRuntimeOutput.incomplete();
            }
            if (separatorBytes > 0L) {
                output.append('\n');
            }
            output.append(parsed.payload);
            outputBytes += separatorBytes + encoded.length;
            previousEventSequence = row.eventSequence;
            previousRuntimeSequence = parsed.runtimeSequence;
        }
        return RecoveredRuntimeOutput.complete(output.toString());
    }

    private ParsedOutput parse(OutputRow row) {
        try {
            if (row.payload == null
                    || row.payloadSize
                    != row.payload.getBytes(StandardCharsets.UTF_8).length) {
                return null;
            }
            JsonNode root = MAPPER.readTree(row.payload);
            JsonNode sequence = root.get("runtimeSequence");
            JsonNode payload = root.get("content");
            if (sequence == null || !sequence.canConvertToLong()
                    || sequence.longValue() < 0L
                    || payload == null || !payload.isTextual()) {
                return null;
            }
            return new ParsedOutput(
                    sequence.longValue(), payload.textValue());
        } catch (RuntimeException | java.io.IOException failure) {
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

    private static final class OutputRow {
        private final long eventSequence;
        private final String payload;
        private final int payloadSize;

        private OutputRow(
                long eventSequence, String payload, int payloadSize) {
            this.eventSequence = eventSequence;
            this.payload = payload;
            this.payloadSize = payloadSize;
        }
    }

    private static final class ParsedOutput {
        private final long runtimeSequence;
        private final String payload;

        private ParsedOutput(long runtimeSequence, String payload) {
            this.runtimeSequence = runtimeSequence;
            this.payload = payload;
        }
    }
}
