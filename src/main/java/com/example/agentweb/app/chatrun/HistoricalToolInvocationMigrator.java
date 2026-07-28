package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationKind;
import com.example.agentweb.domain.chatrun.ToolInvocationRepository;
import com.example.agentweb.domain.chatrun.ToolInvocationSource;
import com.example.agentweb.domain.chatrun.ToolInvocationStatus;
import com.example.agentweb.domain.chatrun.ToolInvocationTriggerSource;
import com.example.agentweb.domain.shared.AgentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HistoricalToolInvocationMigrator {

    private final JdbcTemplate jdbc;
    private final ToolInvocationRepository repository;
    private final ObjectMapper mapper;

    public HistoricalToolInvocationMigrator(JdbcTemplate jdbc, ToolInvocationRepository repository,
                                             ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.mapper = mapper;
    }

    public ToolInvocationMigrationReport migrate(boolean dryRun, int batchSize) {
        ToolInvocationMigrationReport report = new ToolInvocationMigrationReport();
        long cursor = dryRun ? 0L : loadCheckpoint();
        while (true) {
            List<MessageRow> rows = jdbc.query("SELECT m.id,m.session_id,s.agent_type,m.timestamp,m.content "
                            + "FROM chat_message m JOIN chat_session s ON s.id=m.session_id "
                            + "WHERE m.role='assistant' AND s.agent_type IN ('CLAUDE','CODEX') AND m.id>? "
                            + "ORDER BY m.id LIMIT ?",
                    (rs, rowNum) -> new MessageRow(rs.getLong(1), rs.getString(2),
                            AgentType.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5)),
                    cursor, Math.max(1, batchSize));
            if (rows.isEmpty()) {
                break;
            }
            for (MessageRow row : rows) {
                parseMessage(row, dryRun, report);
                cursor = row.id;
            }
            if (!dryRun) {
                saveCheckpoint(cursor, report);
            }
        }
        return report;
    }

    private long loadCheckpoint() {
        List<Long> values = jdbc.query("SELECT last_message_id FROM chat_tool_invocation_migration_state "
                        + "WHERE migration_name='chat-tool-invocation-v1'",
                (rs, rowNum) -> rs.getLong(1));
        return values.isEmpty() ? 0L : values.get(0).longValue();
    }

    private void saveCheckpoint(long cursor, ToolInvocationMigrationReport report) {
        jdbc.update("INSERT INTO chat_tool_invocation_migration_state "
                        + "(migration_name,last_message_id,scanned_messages,inserted_invocations,parse_failures,"
                        + "replayed_results,updated_at) VALUES ('chat-tool-invocation-v1',?,?,?,?,?,?) "
                        + "ON CONFLICT(migration_name) DO UPDATE SET last_message_id=excluded.last_message_id,"
                        + "scanned_messages=excluded.scanned_messages,"
                        + "inserted_invocations=excluded.inserted_invocations,"
                        + "parse_failures=excluded.parse_failures,"
                        + "replayed_results=excluded.replayed_results,"
                        + "updated_at=excluded.updated_at",
                cursor, report.getScannedAssistantMessages(), report.getRecognizedCallStarts(),
                report.getInvalidInputs(), report.getReplayedResultsIgnored(), System.currentTimeMillis());
    }

    private void parseMessage(MessageRow row, boolean dryRun, ToolInvocationMigrationReport report) {
        report.scannedMessage();
        Map<Integer, Pending> byBlock = new HashMap<Integer, Pending>();
        Map<String, Pending> byId = new HashMap<String, Pending>();
        int index = 0;
        for (String line : row.content.split("\\R")) {
            try {
                JsonNode root = mapper.readTree(line);
                if ("stream_event".equals(root.path("type").asText())) {
                    JsonNode event = root.path("event");
                    if ("content_block_start".equals(event.path("type").asText())
                            && "tool_use".equals(event.path("content_block").path("type").asText())) {
                        JsonNode block = event.path("content_block");
                        String name = block.path("name").asText();
                        ToolInvocationKind kind = row.provider == AgentType.CODEX && "shell".equals(name)
                                ? ToolInvocationKind.COMMAND_EXECUTION
                                : ("Skill".equals(name) ? ToolInvocationKind.SKILL : ToolInvocationKind.TOOL_USE);
                        Pending pending = new Pending(++index, block.path("id").asText(), kind,
                                kind == ToolInvocationKind.COMMAND_EXECUTION ? null : name,
                                mapper.writeValueAsString(block.path("input")));
                        byBlock.put(event.path("index").asInt(-1), pending);
                        byId.put(pending.callId, pending);
                        report.call(kind);
                    } else if ("content_block_delta".equals(event.path("type").asText())
                            && "input_json_delta".equals(event.path("delta").path("type").asText())) {
                        Pending pending = byBlock.get(event.path("index").asInt(-1));
                        if (pending != null) pending.input.append(event.path("delta").path("partial_json").asText(""));
                    }
                } else if ("user".equals(root.path("type").asText())) {
                    for (JsonNode block : root.path("message").path("content")) {
                        if (!"tool_result".equals(block.path("type").asText())) continue;
                        Pending pending = byId.get(block.path("tool_use_id").asText());
                        if (pending == null) {
                            report.replayedResult();
                        } else {
                            JsonNode content = block.path("content");
                            pending.output = content.isTextual() ? content.asText() : mapper.writeValueAsString(content);
                            pending.error = block.path("is_error").asBoolean(false);
                            pending.completed = true;
                            report.matchedResult(pending.error);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 行是 CLI stderr 或普通文本，不构成迁移解析失败。
            }
        }
        for (Pending pending : byId.values()) {
            save(row, pending, dryRun, report);
        }
    }

    private void save(MessageRow row, Pending pending, boolean dryRun, ToolInvocationMigrationReport report) {
        String input = pending.input.length() == 0 || "{}".equals(pending.input.toString())
                ? pending.initialInput : pending.input.toString();
        String skillName = null;
        try {
            JsonNode parsed = mapper.readTree(input);
            report.parsedInput();
            if (pending.kind == ToolInvocationKind.SKILL && parsed.path("skill").isTextual()) {
                skillName = parsed.path("skill").asText();
            }
        } catch (Exception ex) {
            report.invalidInput();
        }
        if (pending.kind == ToolInvocationKind.SKILL) report.skillName(skillName != null);
        if (!pending.completed) report.incomplete();
        if (dryRun) return;
        long timestamp = parseTimestamp(row.timestamp);
        String safeInput = protectInput(input, 65536);
        String safeOutput = pending.output == null ? null : limit(pending.output, 65536);
        int outputSize = pending.output == null ? 0 : pending.output.length();
        repository.save(ToolInvocation.builder().sessionId(row.sessionId).provider(row.provider)
                .providerCallId(pending.callId).invocationIndex(pending.index).invocationKind(pending.kind)
                .toolName(pending.toolName).skillName(skillName).triggerSource(ToolInvocationTriggerSource.AGENT)
                .inputJson(safeInput).outputText(safeOutput)
                .inputTruncated(input.length() > 65536).outputTruncated(outputSize > 65536)
                .outputOriginalSize(pending.output == null ? null : outputSize)
                .status(!pending.completed ? ToolInvocationStatus.INCOMPLETE
                        : pending.error ? ToolInvocationStatus.FAILED : ToolInvocationStatus.SUCCEEDED)
                .error(pending.error).providerItemType(pending.kind == ToolInvocationKind.COMMAND_EXECUTION
                        ? "command_execution" : "tool_use")
                .startedAt(timestamp).completedAt(pending.completed ? timestamp : null)
                .createdAt(timestamp).updatedAt(timestamp).source(ToolInvocationSource.HISTORY_MIGRATION)
                .sourceMessageId(row.id).migrationConfidence("HIGH").build());
    }

    private String protectInput(String input, int maximum) {
        try {
            JsonNode root = mapper.readTree(input);
            redact(root);
            return limit(mapper.writeValueAsString(root), maximum);
        } catch (Exception ignored) {
            return limit(input, maximum);
        }
    }

    private void redact(JsonNode node) {
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().replace("_", "").toLowerCase();
                if (key.matches(".*(password|token|secret|apikey|authorization|cookie|privatekey|credential).*$")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(field.getKey(), "[REDACTED]");
                } else {
                    redact(field.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) redact(child);
        }
    }

    private String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private long parseTimestamp(String timestamp) {
        try { return Instant.parse(timestamp).toEpochMilli(); }
        catch (Exception ignored) { return System.currentTimeMillis(); }
    }

    private static final class MessageRow {
        private final long id; private final String sessionId; private final AgentType provider;
        private final String timestamp; private final String content;
        private MessageRow(long id, String sessionId, AgentType provider, String timestamp, String content) {
            this.id=id; this.sessionId=sessionId; this.provider=provider; this.timestamp=timestamp; this.content=content;
        }
    }
    private static final class Pending {
        private final int index; private final String callId; private final ToolInvocationKind kind;
        private final String toolName; private final String initialInput; private final StringBuilder input=new StringBuilder();
        private String output; private boolean error; private boolean completed;
        private Pending(int index,String callId,ToolInvocationKind kind,String toolName,String initialInput) {
            this.index=index;this.callId=callId;this.kind=kind;this.toolName=toolName;this.initialInput=initialInput;
        }
    }
}
