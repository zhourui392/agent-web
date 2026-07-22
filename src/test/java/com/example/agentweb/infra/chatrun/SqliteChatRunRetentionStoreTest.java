package com.example.agentweb.infra.chatrun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SqliteChatRunRetentionStoreTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteChatRunRetentionStore store;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("chat-run-retention.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteChatRunRepositoryTest.createSchema(jdbc);
        store = new SqliteChatRunRetentionStore(jdbc);
    }

    @Test
    void delete_should_keep_active_recent_and_runs_with_retained_events() {
        long old = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
        long recent = Instant.parse("2026-07-21T00:00:00Z").toEpochMilli();
        insertRun("terminal-old", "s1", "k1", "FAILED", old, old);
        insertRun("active-old", "s2", "k2", "RUNNING", old, old);
        insertRun("terminal-recent", "s3", "k3", "SUCCEEDED", recent, recent);
        insertRun("terminal-event", "s4", "k4", "CANCELLED", old, old);
        jdbc.update("INSERT INTO chat_run_event "
                        + "(run_id, seq, event_type, payload, payload_size, created_at) VALUES (?,?,?,?,?,?)",
                "terminal-event", 1L, "terminal", "{}", 2, old);

        int deleted = store.deleteTerminalRunsBefore(
                Instant.parse("2026-07-01T00:00:00Z"), 500);

        assertEquals(1, deleted);
        assertEquals(0, count("terminal-old"));
        assertEquals(1, count("active-old"));
        assertEquals(1, count("terminal-recent"));
        assertEquals(1, count("terminal-event"));
    }

    private void insertRun(String id, String sessionId, String key, String status,
                           long createdAt, long updatedAt) {
        Long assistantMessageId = "SUCCEEDED".equals(status) ? Long.valueOf(createdAt) : null;
        String failureCode = "FAILED".equals(status) ? "EXECUTION_FAILED" : null;
        String errorMessage = "FAILED".equals(status) ? "failed" : null;
        Long startedAt = "PENDING".equals(status) ? null : Long.valueOf(createdAt);
        Long finishedAt = "PENDING".equals(status) || "RUNNING".equals(status)
                || "CANCEL_REQUESTED".equals(status) ? null : Long.valueOf(updatedAt);
        jdbc.update("INSERT INTO chat_run (id, session_id, user_message_id, assistant_message_id, "
                        + "idempotency_key, recall_enabled, status, last_event_seq, exit_code, failure_code, "
                        + "error_message, created_at, started_at, cancel_requested_at, finished_at, updated_at, version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, sessionId, Math.abs(createdAt), assistantMessageId, key, 1, status, 0L, null,
                failureCode, errorMessage, createdAt, startedAt, null, finishedAt, updatedAt, 0L);
    }

    private int count(String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_run WHERE id=?", Integer.class, id);
        return count == null ? 0 : count.intValue();
    }
}
