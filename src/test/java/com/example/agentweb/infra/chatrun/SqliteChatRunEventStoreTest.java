package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.EventSequenceRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SqliteChatRunEventStoreTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteChatRunEventStore store;
    private final ChatRunId runId = ChatRunId.of("run-1");
    private final Instant now = Instant.parse("2026-07-22T10:00:00Z");

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("chat-run-event.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteChatRunRepositoryTest.createSchema(jdbc);
        store = new SqliteChatRunEventStore(jdbc);
    }

    @Test
    void append_and_replay_should_preserve_assigned_order_and_utf8_size() {
        List<ChatRunEventDraft> drafts = Arrays.asList(
                new ChatRunEventDraft("run_status", "{\"status\":\"RUNNING\"}"),
                new ChatRunEventDraft("chunk", "中文"));

        List<ChatRunEvent> appended = store.appendAssigned(runId,
                new EventSequenceRange(1L, 2L), drafts, now);

        assertEquals(2, appended.size());
        assertEquals(1L, appended.get(0).getSeq());
        assertEquals(2L, appended.get(1).getSeq());
        assertEquals(6, appended.get(1).getPayloadSize());
        assertEquals(1L, store.findEarliestSequence(runId));

        List<ChatRunEvent> replay = store.findAfterThrough(runId, 1L, 2L, 100);
        assertEquals(1, replay.size());
        assertEquals("chunk", replay.get(0).getEventType());
        assertEquals("中文", replay.get(0).getPayload());
    }

    @Test
    void append_should_reject_range_size_mismatch_and_duplicate_sequence() {
        List<ChatRunEventDraft> one = Arrays.asList(new ChatRunEventDraft("chunk", "a"));

        assertThrows(IllegalArgumentException.class,
                () -> store.appendAssigned(runId, new EventSequenceRange(1L, 2L), one, now));

        store.appendAssigned(runId, new EventSequenceRange(1L, 1L), one, now);
        assertThrows(RuntimeException.class,
                () -> store.appendAssigned(runId, new EventSequenceRange(1L, 1L), one, now));
    }

    @Test
    void empty_store_should_report_zero_earliest_sequence() {
        assertEquals(0L, store.findEarliestSequence(runId));
        assertEquals(0, store.findAfterThrough(runId, 0L, 10L, 100).size());
    }

    @Test
    void retention_should_delete_only_old_terminal_run_events() {
        Instant old = now.minusSeconds(48L * 60L * 60L);
        insertRun("terminal", "FAILED", old);
        insertRun("active", "RUNNING", old);
        store.appendAssigned(ChatRunId.of("terminal"), new EventSequenceRange(1L, 1L),
                Arrays.asList(new ChatRunEventDraft("terminal", "{}")), old);
        store.appendAssigned(ChatRunId.of("active"), new EventSequenceRange(1L, 1L),
                Arrays.asList(new ChatRunEventDraft("chunk", "a")), old);

        int deleted = store.deleteBefore(now.minusSeconds(24L * 60L * 60L), 5_000);

        assertEquals(1, deleted);
        assertEquals(0, store.findAfterThrough(ChatRunId.of("terminal"), 0L, 1L, 10).size());
        assertEquals(1, store.findAfterThrough(ChatRunId.of("active"), 0L, 1L, 10).size());
    }

    private void insertRun(String id, String status, Instant createdAt) {
        String failureCode = "FAILED".equals(status) ? "EXECUTION_FAILED" : null;
        String errorMessage = "FAILED".equals(status) ? "failed" : null;
        Long finishedAt = "FAILED".equals(status) ? Long.valueOf(createdAt.toEpochMilli()) : null;
        jdbc.update("INSERT INTO chat_run (id, session_id, user_message_id, idempotency_key, recall_enabled, "
                        + "status, last_event_seq, failure_code, error_message, created_at, started_at, "
                        + "finished_at, updated_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "session-" + id, Math.abs(createdAt.toEpochMilli()), "key-" + id, 1, status, 1L,
                failureCode, errorMessage, createdAt.toEpochMilli(), createdAt.toEpochMilli(),
                finishedAt, createdAt.toEpochMilli(), 0L);
    }
}
