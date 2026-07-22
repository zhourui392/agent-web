package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunDiagnosticView;
import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunEventConsumer;
import com.example.agentweb.app.chatrun.ChatRunEventSubscription;
import com.example.agentweb.app.chatrun.ChatRunMetricsOverview;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.infra.ResumableChatStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRun 指标读模型的 SQLite 轻量集成测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SqliteChatRunMetricsQueryServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private InMemoryChatRunEventHub eventHub;
    private SqliteChatRunMetricsQueryService queryService;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("chat-run-metrics.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_session (id TEXT PRIMARY KEY, agent_type TEXT NOT NULL)");
        SqliteChatRunRepositoryTest.createSchema(jdbc);

        ResumableChatStreamProperties properties = new ResumableChatStreamProperties();
        properties.setSubscriberMaxEvents(10);
        properties.setSubscriberMaxBytes(1024);
        eventHub = new InMemoryChatRunEventHub(properties, new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        });
        queryService = new SqliteChatRunMetricsQueryService(jdbc, eventHub);
    }

    @Test
    void overview_should_aggregate_run_event_and_live_subscriber_metrics() {
        insertSession("session-pending", "CODEX");
        insertSession("session-running", "CLAUDE");
        insertSession("session-cancelling", "CODEX");
        insertSession("session-succeeded", "CODEX");
        insertSession("session-failed", "CLAUDE");
        insertSession("session-cancelled", "CODEX");
        insertSession("session-interrupted", "CLAUDE");
        insertRun("run-pending", "session-pending", "PENDING", 0L,
                null, null, null, null, 1000L, null, null);
        insertRun("run-running", "session-running", "RUNNING", 2L,
                null, null, null, null, 2000L, 2100L, null);
        insertRun("run-cancelling", "session-cancelling", "CANCEL_REQUESTED", 0L,
                null, null, null, null, 3000L, 3100L, null);
        insertRun("run-succeeded", "session-succeeded", "SUCCEEDED", 1L,
                101L, null, null, 0, 4000L, 5000L, 7000L);
        insertRun("run-failed", "session-failed", "FAILED", 0L,
                null, "CLI_EXIT", "exit 17", 17, 5000L, 6000L, 10000L);
        insertRun("run-cancelled", "session-cancelled", "CANCELLED", 0L,
                null, null, null, null, 6000L, 7000L, 13000L);
        insertRun("run-interrupted", "session-interrupted", "INTERRUPTED", 0L,
                null, "ORPHAN_RECOVERY", "service restarted", null,
                7000L, 8000L, 16000L);
        insertEvent("run-running", 1L, "chunk", "1234", 2200L);
        insertEvent("run-running", 2L, "chunk", "123456", 2300L);
        insertEvent("run-succeeded", 1L, "done", "123", 6900L);
        openSubscriber("run-running");
        openSubscriber("run-running");
        openSubscriber("run-pending");

        ChatRunMetricsOverview overview = queryService.overview();

        assertEquals(3L, overview.getActiveTotal());
        assertEquals(1L, overview.getActiveByStatus().get("PENDING"));
        assertEquals(1L, overview.getActiveByStatus().get("RUNNING"));
        assertEquals(1L, overview.getActiveByStatus().get("CANCEL_REQUESTED"));
        assertEquals(2L, overview.getActiveByAgentType().get("CODEX"));
        assertEquals(1L, overview.getActiveByAgentType().get("CLAUDE"));
        assertEquals(1L, overview.getTerminalByStatus().get("SUCCEEDED"));
        assertEquals(1L, overview.getTerminalByStatus().get("FAILED"));
        assertEquals(1L, overview.getTerminalByStatus().get("CANCELLED"));
        assertEquals(1L, overview.getTerminalByStatus().get("INTERRUPTED"));
        assertEquals(1L, overview.getFailureByCode().get("CLI_EXIT"));
        assertEquals(1L, overview.getFailureByCode().get("ORPHAN_RECOVERY"));
        assertEquals(Long.valueOf(5L), overview.getAvgDurationSeconds());
        assertEquals(Long.valueOf(8L), overview.getMaxDurationSeconds());
        assertEquals(3L, overview.getEventRows());
        assertEquals(13L, overview.getEventPayloadBytes());
        assertEquals(3, overview.getLiveSubscribers());
        assertEquals(0L, overview.getSlowConsumerClosedTotal());
    }

    @Test
    void overview_should_return_empty_metrics_when_database_has_no_runs() {
        ChatRunMetricsOverview overview = queryService.overview();

        assertEquals(0L, overview.getActiveTotal());
        assertTrue(overview.getActiveByStatus().isEmpty());
        assertTrue(overview.getActiveByAgentType().isEmpty());
        assertTrue(overview.getTerminalByStatus().isEmpty());
        assertTrue(overview.getFailureByCode().isEmpty());
        assertNull(overview.getAvgDurationSeconds());
        assertNull(overview.getMaxDurationSeconds());
        assertEquals(0L, overview.getEventRows());
        assertEquals(0L, overview.getEventPayloadBytes());
        assertEquals(0, overview.getLiveSubscribers());
    }

    @Test
    void recent_and_single_run_diagnostics_should_include_persistence_and_live_state() {
        insertSession("session-old", "CODEX");
        insertSession("session-new", "CLAUDE");
        insertRun("run-old", "session-old", "RUNNING", 2L,
                201L, null, null, null, 1000L, 1100L, null);
        insertRun("run-new", "session-new", "FAILED", 1L,
                null, "CLI_EXIT", "exit 9", 9, 2000L, 2100L, 3100L);
        insertEvent("run-old", 1L, "chunk", "first", 1200L);
        insertEvent("run-old", 2L, "chunk", "second", 1300L);
        insertEvent("run-new", 1L, "error", "failed", 3000L);
        openSubscriber("run-old");
        openSubscriber("run-old");

        List<ChatRunDiagnosticView> recent = queryService.recentRuns(1);
        Optional<ChatRunDiagnosticView> old = queryService.diagnose("run-old");

        assertEquals(1, recent.size());
        assertEquals("run-new", recent.get(0).getRunId());
        assertEquals("FAILED", recent.get(0).getStatus());
        assertEquals(1L, recent.get(0).getEventCount());
        assertEquals(Long.valueOf(3000L), recent.get(0).getLastEventAt());
        assertEquals("CLI_EXIT", recent.get(0).getFailureCode());
        assertEquals(Integer.valueOf(9), recent.get(0).getExitCode());
        assertFalse(recent.get(0).isAssistantPersisted());

        assertTrue(old.isPresent());
        assertEquals("session-old", old.get().getSessionId());
        assertEquals("CODEX", old.get().getAgentType());
        assertEquals(2L, old.get().getLastEventSeq());
        assertEquals(2L, old.get().getEventCount());
        assertEquals(Long.valueOf(1300L), old.get().getLastEventAt());
        assertEquals(2, old.get().getLiveSubscribers());
        assertEquals(Long.valueOf(201L), old.get().getAssistantMessageId());
        assertTrue(old.get().isAssistantPersisted());
        assertFalse(queryService.diagnose("missing-run").isPresent());
    }

    private void insertSession(String id, String agentType) {
        jdbc.update("INSERT INTO chat_session(id, agent_type) VALUES (?, ?)", id, agentType);
    }

    private void insertRun(String id, String sessionId, String status, long lastEventSeq,
                           Long assistantMessageId, String failureCode, String errorMessage,
                           Integer exitCode, long createdAt, Long startedAt, Long finishedAt) {
        jdbc.update("INSERT INTO chat_run(id, session_id, user_message_id, assistant_message_id, "
                        + "idempotency_key, recall_enabled, status, last_event_seq, exit_code, failure_code, "
                        + "error_message, created_at, started_at, finished_at, updated_at, version) "
                        + "VALUES (?, ?, 1, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, sessionId, assistantMessageId, "key-" + id, status, lastEventSeq, exitCode,
                failureCode, errorMessage, createdAt, startedAt, finishedAt, createdAt);
    }

    private void insertEvent(String runId, long seq, String type, String payload, long createdAt) {
        jdbc.update("INSERT INTO chat_run_event(run_id, seq, event_type, payload, payload_size, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                runId, seq, type, payload, payload.length(), createdAt);
    }

    private ChatRunEventSubscription openSubscriber(String runId) {
        return eventHub.open(ChatRunId.of(runId), new ChatRunEventConsumer() {
            @Override
            public void accept(ChatRunEvent event) {
            }

            @Override
            public void overflow() {
            }
        });
    }
}
