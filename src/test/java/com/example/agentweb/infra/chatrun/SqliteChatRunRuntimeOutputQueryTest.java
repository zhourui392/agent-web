package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.RecoveredRuntimeOutput;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 输出恢复查询的真实 SQLite、顺序、损坏与容量测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteChatRunRuntimeOutputQueryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve("runtime-output.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
    }

    @Test
    void loadShouldRecoverOnlyExactRunOutputInPersistedOrder() {
        insert("run-1", 1L, "runtime_started",
                payload(1L, "STARTED", "started"));
        insert("run-1", 2L, "runtime_output",
                payload(2L, "OUTPUT", "raw provider JSON must be ignored"));
        insert("run-1", 3L, "agent_chunk",
                chunkPayload(2L, "first"));
        insert("another-run", 1L, "agent_chunk",
                chunkPayload(1L, "foreign"));
        insert("run-1", 4L, "runtime_diagnostic",
                payload(3L, "DIAGNOSTIC", "ignored"));
        insert("run-1", 5L, "agent_chunk",
                chunkPayload(4L, "第二"));
        SqliteChatRunRuntimeOutputQuery query =
                new SqliteChatRunRuntimeOutputQuery(jdbc, 4096L);

        RecoveredRuntimeOutput result = query.load(
                ChatRunId.of("run-1"),
                new RuntimeHandle("run-1", "handle-1"));

        assertTrue(result.isComplete());
        String content = result.getContent();
        assertTrue(content.contains("\"text_delta\""));
        assertTrue(content.contains("first"));
        assertTrue(content.contains("第二"));
    }

    @Test
    void shouldRecoverShellCommandAndBoundedOutputWhenLoadingRuntimeOutput()
            throws Exception {
        // Given
        insert("run-shell", 1L, "tool_started",
                "{\"runtimeSequence\":1,\"tool\":\"shell\","
                        + "\"callId\":\"tool-1\",\"status\":\"RUNNING\","
                        + "\"commandContent\":\"mvn -q test\"}");
        insert("run-shell", 2L, "tool_finished",
                "{\"runtimeSequence\":2,\"tool\":\"shell\","
                        + "\"callId\":\"tool-1\",\"status\":\"SUCCEEDED\","
                        + "\"outputContent\":\"Tests run: 12\\n... (已截断)\","
                        + "\"outputTruncated\":true}");
        SqliteChatRunRuntimeOutputQuery query =
                new SqliteChatRunRuntimeOutputQuery(jdbc, 4096L);

        // When
        RecoveredRuntimeOutput result = query.load(
                ChatRunId.of("run-shell"),
                new RuntimeHandle("run-shell", "handle-shell"));

        // Then
        assertTrue(result.isComplete());
        String[] events = result.getContent().split("\\n");
        assertEquals(3, events.length);
        assertEquals("{\"command\":\"mvn -q test\"}",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(events[1]).path("event").path("delta")
                        .path("partial_json").asText());
        assertEquals("Tests run: 12\n... (已截断)",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(events[2]).path("tool_use_result").asText());
    }

    @Test
    void malformedOrNonMonotonicOutputShouldFailClosed() {
        insert("run-malformed", 1L, "agent_chunk", "not-json");
        SqliteChatRunRuntimeOutputQuery query =
                new SqliteChatRunRuntimeOutputQuery(jdbc, 1024L);

        RecoveredRuntimeOutput malformed = query.load(
                ChatRunId.of("run-malformed"),
                new RuntimeHandle("run-malformed", "handle-malformed"));

        assertFalse(malformed.isComplete());

        insert("run-sequence", 1L, "agent_chunk",
                chunkPayload(4L, "first"));
        insert("run-sequence", 2L, "agent_chunk",
                chunkPayload(4L, "duplicate"));

        RecoveredRuntimeOutput duplicate = query.load(
                ChatRunId.of("run-sequence"),
                new RuntimeHandle("run-sequence", "handle-sequence"));

        assertFalse(duplicate.isComplete());
    }

    @Test
    void utf8OutputOverConfiguredLimitShouldReturnIncompleteWithoutPartialText() {
        insert("run-limit", 1L, "agent_chunk",
                chunkPayload(1L, "中文"));
        SqliteChatRunRuntimeOutputQuery query =
                new SqliteChatRunRuntimeOutputQuery(jdbc, 5L);

        RecoveredRuntimeOutput result = query.load(
                ChatRunId.of("run-limit"),
                new RuntimeHandle("run-limit", "handle-limit"));

        assertFalse(result.isComplete());
        assertEquals("", result.getContent());
    }

    @Test
    void mismatchedHandleShouldBeRejectedBeforeReadingAnotherExecution() {
        SqliteChatRunRuntimeOutputQuery query =
                new SqliteChatRunRuntimeOutputQuery(jdbc, 1024L);

        assertThrows(IllegalArgumentException.class, () -> query.load(
                ChatRunId.of("run-1"),
                new RuntimeHandle("another-run", "handle-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new SqliteChatRunRuntimeOutputQuery(jdbc, 0L));
    }

    private void insert(
            String runId, long sequence, String eventType,
            String payload) {
        byte[] encoded = payload.getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO chat_run_event "
                        + "(run_id, seq, event_type, payload, payload_size, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                runId, Long.valueOf(sequence), eventType,
                payload, Integer.valueOf(encoded.length), Long.valueOf(sequence));
    }

    private static String payload(
            long runtimeSequence, String runtimeType,
            String value) {
        return "{\"runtimeSequence\":" + runtimeSequence
                + ",\"runtimeType\":\"" + runtimeType
                + "\",\"payload\":\"" + value + "\"}";
    }

    private static String chunkPayload(
            long runtimeSequence, String content) {
        return "{\"runtimeSequence\":" + runtimeSequence
                + ",\"content\":\"" + content + "\"}";
    }
}
