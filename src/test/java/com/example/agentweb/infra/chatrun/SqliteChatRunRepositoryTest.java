package com.example.agentweb.infra.chatrun;

import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.DuplicateChatRunSubmissionException;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertEquals(RunOrigin.CHAT, loaded.getRunOrigin());
        assertFalse(loaded.getExecutionContextReference().isPresent());
        assertEquals(ChatRunStatus.PENDING, loaded.getStatus());
        assertEquals(2L, loaded.getLastEventSeq());
        assertEquals(0L, loaded.getVersion());
        assertTrue(repository.findBySessionAndIdempotencyKey("session-1", "key-1").isPresent());
        assertTrue(repository.findActiveBySessionId("session-1").isPresent());
    }

    @Test
    void workbench_origin_and_execution_context_should_round_trip_losslessly() {
        ChatRun run = ChatRun.submit(
                ChatRunId.of("run-workbench-1"), "stage-session-1", 21L,
                "key-workbench-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:stage-implementation", "run-workbench-1"),
                CREATED_AT);

        repository.add(run);

        ChatRun loaded = repository.findById(run.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(RunOrigin.WORKBENCH, loaded.getRunOrigin());
        assertEquals("workbench-1:stage-implementation",
                loaded.getExecutionContextReference().getOriginReference());
        assertEquals("run-workbench-1",
                loaded.getExecutionContextReference().getExecutionContextId());
        assertFalse(loaded.isRecallEnabled());
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

    @Test
    void update_should_retry_sqlite_shared_cache_lock_and_preserve_version_semantics() {
        JdbcTemplate lockingJdbc = mock(JdbcTemplate.class);
        when(lockingJdbc.update(anyString(), any(Object[].class)))
                .thenThrow(sqliteFailure(SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE))
                .thenThrow(sqliteFailure(SQLiteErrorCode.SQLITE_BUSY))
                .thenReturn(1);
        SqliteChatRunRepository lockingRepository = new SqliteChatRunRepository(lockingJdbc);
        ChatRun run = newRun("run-retry", "session-retry", "key-retry", 11L);
        run.start(CREATED_AT.plusSeconds(1));

        lockingRepository.update(run);

        assertEquals(1L, run.getVersion());
        verify(lockingJdbc, times(3)).update(anyString(), any(Object[].class));
    }

    @Test
    void update_should_not_retry_non_lock_sqlite_failure() {
        JdbcTemplate failingJdbc = mock(JdbcTemplate.class);
        UncategorizedSQLException failure = sqliteFailure(SQLiteErrorCode.SQLITE_CONSTRAINT);
        when(failingJdbc.update(anyString(), any(Object[].class))).thenThrow(failure);
        SqliteChatRunRepository failingRepository = new SqliteChatRunRepository(failingJdbc);
        ChatRun run = newRun("run-failure", "session-failure", "key-failure", 11L);
        run.start(CREATED_AT.plusSeconds(1));

        assertEquals(failure, assertThrows(UncategorizedSQLException.class,
                () -> failingRepository.update(run)));
        assertEquals(0L, run.getVersion());
        verify(failingJdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void update_should_stop_after_bounded_sqlite_lock_retries() {
        JdbcTemplate lockedJdbc = mock(JdbcTemplate.class);
        UncategorizedSQLException failure = sqliteFailure(
                SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE);
        when(lockedJdbc.update(anyString(), any(Object[].class))).thenThrow(failure);
        SqliteChatRunRepository lockedRepository = new SqliteChatRunRepository(lockedJdbc);
        ChatRun run = newRun("run-locked", "session-locked", "key-locked", 11L);
        run.start(CREATED_AT.plusSeconds(1));

        assertEquals(failure, assertThrows(UncategorizedSQLException.class,
                () -> lockedRepository.update(run)));
        assertEquals(0L, run.getVersion());
        verify(lockedJdbc, times(6)).update(anyString(), any(Object[].class));
    }

    private ChatRun newRun(String runId, String sessionId, String key, long messageId) {
        return ChatRun.submit(ChatRunId.of(runId), sessionId, messageId, key, CREATED_AT);
    }

    private UncategorizedSQLException sqliteFailure(SQLiteErrorCode errorCode) {
        return new UncategorizedSQLException("update chat run", "UPDATE chat_run",
                new SQLiteException(errorCode.message, errorCode));
    }

    static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE chat_run ("
                + "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, user_message_id INTEGER NOT NULL, "
                + "assistant_message_id INTEGER, idempotency_key TEXT NOT NULL, "
                + "recall_enabled INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL, "
                + "run_origin TEXT NOT NULL DEFAULT 'CHAT', origin_reference TEXT, "
                + "execution_context_id TEXT, "
                + "last_event_seq INTEGER NOT NULL DEFAULT 0, exit_code INTEGER, failure_code TEXT, "
                + "error_message TEXT, created_at INTEGER NOT NULL, started_at INTEGER, "
                + "cancel_requested_at INTEGER, finished_at INTEGER, updated_at INTEGER NOT NULL, "
                + "version INTEGER NOT NULL DEFAULT 0, UNIQUE(session_id, idempotency_key), "
                + "UNIQUE(assistant_message_id), CHECK(last_event_seq >= 0), "
                + "CHECK((run_origin='CHAT' AND origin_reference IS NULL "
                + "AND execution_context_id IS NULL) OR (run_origin='WORKBENCH' "
                + "AND origin_reference IS NOT NULL AND execution_context_id IS NOT NULL)), "
                + "CHECK(status IN ('PENDING','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED'))) ");
        jdbc.execute("CREATE UNIQUE INDEX uk_chat_run_active_session ON chat_run(session_id) "
                + "WHERE status IN ('PENDING','RUNNING','CANCEL_REQUESTED')");
        jdbc.execute("CREATE TABLE chat_run_event (run_id TEXT NOT NULL, seq INTEGER NOT NULL, "
                + "event_type TEXT NOT NULL, payload TEXT NOT NULL, payload_size INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, PRIMARY KEY(run_id, seq), CHECK(seq > 0), "
                + "CHECK(payload_size >= 0))");
    }
}
