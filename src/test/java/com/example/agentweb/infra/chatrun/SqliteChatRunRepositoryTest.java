package com.example.agentweb.infra.chatrun;

import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.DuplicateChatRunSubmissionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SqliteChatRunRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-22T10:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteChatRunRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("chat-run.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        repository = new SqliteChatRunRepository(jdbc);
    }

    @Test
    void add_and_find_should_round_trip_aggregate() {
        ChatRun run = newRun("run-1", "session-1", "key-1", 11L);
        run.allocateEventSequence(2, CREATED_AT.plusSeconds(1));

        repository.add(run);

        ChatRun loaded = repository.findById(ChatRunId.of("run-1")).orElseThrow(AssertionError::new);
        assertEquals("session-1", loaded.getSessionId());
        assertEquals(11L, loaded.getUserMessageId());
        assertEquals("key-1", loaded.getIdempotencyKey());
        assertEquals(ChatRunStatus.PENDING, loaded.getStatus());
        assertEquals(2L, loaded.getLastEventSeq());
        assertEquals(0L, loaded.getVersion());
        assertTrue(repository.findBySessionAndIdempotencyKey("session-1", "key-1").isPresent());
        assertTrue(repository.findActiveBySessionId("session-1").isPresent());
    }

    @Test
    void active_partial_unique_index_should_reject_second_active_run() {
        repository.add(newRun("run-1", "session-1", "key-1", 11L));

        assertThrows(ActiveChatRunExistsException.class,
                () -> repository.add(newRun("run-2", "session-1", "key-2", 12L)));
    }

    @Test
    void idempotency_unique_index_should_have_distinct_domain_error() {
        repository.add(newRun("run-1", "session-1", "key-1", 11L));
        ChatRun first = repository.findById(ChatRunId.of("run-1")).orElseThrow(AssertionError::new);
        first.start(CREATED_AT.plusSeconds(1));
        first.succeed(21L, 0, CREATED_AT.plusSeconds(2));
        repository.update(first);

        assertThrows(DuplicateChatRunSubmissionException.class,
                () -> repository.add(newRun("run-2", "session-1", "key-1", 12L)));
    }

    @Test
    void terminal_run_should_release_active_session_slot() {
        ChatRun first = newRun("run-1", "session-1", "key-1", 11L);
        repository.add(first);
        first.start(CREATED_AT.plusSeconds(1));
        first.succeed(21L, 0, CREATED_AT.plusSeconds(2));

        repository.update(first);
        repository.add(newRun("run-2", "session-1", "key-2", 12L));

        assertEquals("run-2", repository.findActiveBySessionId("session-1")
                .orElseThrow(AssertionError::new).getId().getValue());
        assertEquals(1L, first.getVersion());
    }

    @Test
    void update_should_reject_stale_aggregate_version() {
        ChatRun original = newRun("run-1", "session-1", "key-1", 11L);
        repository.add(original);
        ChatRun copyA = repository.findById(ChatRunId.of("run-1")).orElseThrow(AssertionError::new);
        ChatRun copyB = repository.findById(ChatRunId.of("run-1")).orElseThrow(AssertionError::new);
        copyA.start(CREATED_AT.plusSeconds(1));
        copyB.start(CREATED_AT.plusSeconds(1));

        repository.update(copyA);

        assertThrows(IllegalStateException.class, () -> repository.update(copyB));
        assertFalse(repository.findById(ChatRunId.of("missing")).isPresent());
    }

    private ChatRun newRun(String runId, String sessionId, String key, long messageId) {
        return ChatRun.submit(ChatRunId.of(runId), sessionId, messageId, key, CREATED_AT);
    }

    static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE chat_run ("
                + "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, user_message_id INTEGER NOT NULL, "
                + "assistant_message_id INTEGER, idempotency_key TEXT NOT NULL, "
                + "recall_enabled INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL, "
                + "last_event_seq INTEGER NOT NULL DEFAULT 0, exit_code INTEGER, failure_code TEXT, "
                + "error_message TEXT, created_at INTEGER NOT NULL, started_at INTEGER, "
                + "cancel_requested_at INTEGER, finished_at INTEGER, updated_at INTEGER NOT NULL, "
                + "version INTEGER NOT NULL DEFAULT 0, UNIQUE(session_id, idempotency_key), "
                + "UNIQUE(assistant_message_id), CHECK(last_event_seq >= 0), "
                + "CHECK(status IN ('PENDING','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED'))) ");
        jdbc.execute("CREATE UNIQUE INDEX uk_chat_run_active_session ON chat_run(session_id) "
                + "WHERE status IN ('PENDING','RUNNING','CANCEL_REQUESTED')");
        jdbc.execute("CREATE TABLE chat_run_event (run_id TEXT NOT NULL, seq INTEGER NOT NULL, "
                + "event_type TEXT NOT NULL, payload TEXT NOT NULL, payload_size INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, PRIMARY KEY(run_id, seq), CHECK(seq > 0), "
                + "CHECK(payload_size >= 0))");
    }
}
