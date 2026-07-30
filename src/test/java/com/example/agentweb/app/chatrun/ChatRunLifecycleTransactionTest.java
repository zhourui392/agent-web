package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentStateCheckpointPayload;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.AgentUsage;
import com.example.agentweb.app.common.AfterCommitExecutor;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpoint;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.infra.SqliteSessionRepo;
import com.example.agentweb.infra.chatrun.SqliteChatRunEventStore;
import com.example.agentweb.infra.chatrun.SqliteChatRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Transactional proof that checkpoint persistence and a successful assistant boundary cannot split.
 *
 * @author alex
 * @since 2026-07-29
 */
class ChatRunLifecycleTransactionTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private SqliteChatRunRepository runRepository;
    private SessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("lifecycle-transaction.db"));
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createSchema();
        runRepository = new SqliteChatRunRepository(jdbc);
        sessionRepository = new SqliteSessionRepo(jdbc,
                new CurrentUserProvider(() -> Optional.empty()));
    }

    @Test
    void checkpointFailure_shouldRollbackAssistantRecallRunSuccessAndTerminalEvent() {
        jdbc.update("INSERT INTO chat_session(id, agent_type, working_dir, created_at) "
                + "VALUES ('session-1', 'NATIVE', '/workspace', ?)", NOW.toString());
        jdbc.update("INSERT INTO chat_message(session_id, role, content, timestamp) "
                + "VALUES ('session-1', 'user', 'question', ?)", NOW.minusSeconds(2).toString());
        long userMessageId = jdbc.queryForObject(
                "SELECT MAX(id) FROM chat_message", Long.class);
        ChatRun run = ChatRun.submit(ChatRunId.of("run-1"), "session-1", userMessageId,
                "key-1", NOW.minusSeconds(2));
        run.start(NOW.minusSeconds(1));
        runRepository.add(run);
        DiagnosisCheckpointRepository failingCheckpointRepository = failingCheckpointRepository();
        ChatRunEventAppender eventAppender = new ChatRunEventAppender(runRepository,
                new SqliteChatRunEventStore(jdbc), mock(ChatRunEventHub.class),
                new AfterCommitExecutor());
        ChatRunLifecycleService service = new ChatRunLifecycleService(runRepository,
                sessionRepository, failingCheckpointRepository, eventAppender,
                Clock.fixed(NOW, ZoneOffset.UTC));
        AgentExecutionResult result = new AgentExecutionResult(
                AgentStreamResult.completed(0),
                new AgentStateCheckpointPayload("state", "v1"),
                new AgentUsage(1L, 1L, 0L), "SUCCESS", "");

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(
                ignored -> service.complete(ChatRunId.of("run-1"), "answer", result,
                        "{\"query\":\"q\"}")));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message "
                + "WHERE session_id='session-1' AND role='assistant'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_recall",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_run_event",
                Integer.class));
        ChatRun restored = runRepository.findById(ChatRunId.of("run-1")).orElseThrow();
        assertEquals(ChatRunStatus.RUNNING, restored.getStatus());
        assertNull(restored.getAssistantMessageId());
    }

    private DiagnosisCheckpointRepository failingCheckpointRepository() {
        return new DiagnosisCheckpointRepository() {
            @Override
            public void save(DiagnosisCheckpoint checkpoint) {
                throw new IllegalStateException("checkpoint unavailable");
            }

            @Override
            public Optional<DiagnosisCheckpoint> findLatestValidBefore(
                    String sessionId, long currentUserMessageId) {
                return Optional.empty();
            }

            @Override
            public void deleteCrossingBoundary(String sessionId, long fromMessageId) {
            }

            @Override
            public void deleteBySessionId(String sessionId) {
            }
        };
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE chat_session (id TEXT PRIMARY KEY, agent_type TEXT NOT NULL, "
                + "working_dir TEXT NOT NULL, created_at TEXT NOT NULL, resume_id TEXT, "
                + "last_message_at INTEGER)");
        jdbc.execute("CREATE TABLE chat_message (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, "
                + "timestamp TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE chat_message_recall (message_id INTEGER PRIMARY KEY, "
                + "payload_json TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE chat_run (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
                + "user_message_id INTEGER NOT NULL, assistant_message_id INTEGER, "
                + "idempotency_key TEXT NOT NULL, recall_enabled INTEGER NOT NULL, "
                + "status TEXT NOT NULL, last_event_seq INTEGER NOT NULL, exit_code INTEGER, "
                + "failure_code TEXT, error_message TEXT, created_at INTEGER NOT NULL, "
                + "started_at INTEGER, cancel_requested_at INTEGER, finished_at INTEGER, "
                + "updated_at INTEGER NOT NULL, version INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE chat_run_event (run_id TEXT NOT NULL, seq INTEGER NOT NULL, "
                + "event_type TEXT NOT NULL, payload TEXT NOT NULL, payload_size INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, PRIMARY KEY(run_id, seq))");
    }
}
