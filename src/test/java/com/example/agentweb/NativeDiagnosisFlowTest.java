package com.example.agentweb;

import com.anthropic.agentkit.interfaces.engine.AssistantTurn;
import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.ExitReason;
import com.anthropic.agentkit.interfaces.engine.RunRequest;
import com.anthropic.agentkit.interfaces.engine.RunSummary;
import com.anthropic.agentkit.interfaces.engine.TurnMessage;
import com.anthropic.agentkit.interfaces.engine.UserTurn;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunStreamSink;
import com.example.agentweb.app.chatrun.ChatRunSubscriptionService;
import com.example.agentweb.config.nativeagent.NativeDiagnosisProperties;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisAgentRuntime;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisHistoryMapper;
import com.example.agentweb.infra.nativeagent.NativeRunSummaryMapper;
import com.example.agentweb.infra.nativeagent.NativeRuntimeRegistration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full host-side NATIVE diagnosis flow using the real HTTP, SQLite, ChatRun, router and adapter
 * boundaries with a deterministic in-process engine double.
 *
 * @author alex
 * @since 2026-07-29
 */
@SpringBootTest(properties = {
        "agent.fs.roots=/tmp",
        "agent.native.enabled=false",
        "agent.native.bound-environment=test",
        "agent.native.timeout-seconds=30",
        "agent.chat.resumable-stream.flush-interval-ms=1"
})
@AutoConfigureMockMvc(addFilters = false)
@Import(NativeDiagnosisFlowTest.NativeFlowConfiguration.class)
@Tag("spring-flow")
@ResourceLock("spring-flow-sqlite")
class NativeDiagnosisFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ChatRunRepository runRepository;

    @Autowired
    private ChatRunSubscriptionService subscriptionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedDiagnoseEngine engine;

    @Test
    void nativeFlow_shouldPreserveCheckpointHistoryRewindStopAndCleanup() throws Exception {
        Path workspace = Files.createTempDirectory("native-diagnosis-flow");
        String sessionId = createNativeSession(workspace);

        String firstRunId = submit(sessionId, "first symptom", "native-flow-1");
        awaitTerminal(firstRunId, ChatRunStatus.SUCCEEDED);
        assertPersistedSseReplay(firstRunId);

        String secondRunId = submit(sessionId, "second symptom", "native-flow-2");
        awaitTerminal(secondRunId, ChatRunStatus.SUCCEEDED);

        assertEquals(2, checkpointCount(sessionId));
        assertSecondTurnRequest(firstRunId, secondRunId);

        long secondUserMessageId = messageId(sessionId, "second symptom");
        mvc.perform(delete("/api/chat/session/{id}/messages", sessionId)
                        .param("fromId", String.valueOf(secondUserMessageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2));
        assertEquals(1, checkpointCount(sessionId));

        String branchRunId = submit(sessionId, "branch symptom", "native-flow-3");
        awaitTerminal(branchRunId, ChatRunStatus.SUCCEEDED);
        assertRewoundBranchRequest(branchRunId);

        engine.blockNextRunUntilStopped();
        String stoppedRunId = submit(sessionId, "long diagnosis", "native-flow-4");
        assertTrue(engine.awaitBlockedRunStarted(WAIT_TIMEOUT));
        awaitStatus(stoppedRunId, Set.of(ChatRunStatus.RUNNING));

        mvc.perform(post("/api/chat/runs/{runId}/stop", stoppedRunId))
                .andExpect(status().isAccepted());
        awaitTerminal(stoppedRunId, ChatRunStatus.CANCELLED);

        assertEquals(List.of(stoppedRunId), engine.stoppedRunIds());
        assertEquals(2, checkpointCount(sessionId),
                "stopped runs must not create diagnosis checkpoints");

        mvc.perform(delete("/api/chat/session/{id}", sessionId))
                .andExpect(status().isOk());
        assertEquals(0, checkpointCount(sessionId));
    }

    private String createNativeSession(Path workspace) throws Exception {
        String body = JSON.createObjectNode()
                .put("agentType", "NATIVE")
                .put("workingDir", workspace.toString())
                .put("env", "test")
                .toString();
        String response = mvc.perform(post("/api/chat/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentType").value("NATIVE"))
                .andExpect(jsonPath("$.env").value("test"))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("sessionId").asText();
    }

    private String submit(String sessionId, String message, String idempotencyKey) throws Exception {
        String body = JSON.createObjectNode()
                .put("message", message)
                .put("recall", false)
                .toString();
        String response = mvc.perform(post("/api/chat/session/{id}/runs", sessionId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("runId").asText();
    }

    private void assertSecondTurnRequest(String firstRunId, String secondRunId) {
        List<RunRequest> requests = engine.requests();
        assertTrue(requests.size() >= 2);
        RunRequest first = requests.get(0);
        RunRequest second = requests.get(1);

        assertEquals(firstRunId, first.sessionId());
        assertEquals(secondRunId, second.sessionId());
        assertEquals("test", second.env());
        assertEquals("snapshot-1", second.stateSnapshot());
        assertFalse(second.userMessage().contains("<conversation_history>"));
        assertTrue(second.userMessage().contains("second symptom"));
        assertEquals(2, second.history().size());
        assertEquals("first symptom", assertInstanceOf(UserTurn.class,
                second.history().get(0)).text());
        assertEquals("diagnosis-1", assertInstanceOf(AssistantTurn.class,
                second.history().get(1)).text());
    }

    private void assertPersistedSseReplay(String runId) {
        CollectingSink fullReplay = new CollectingSink();
        subscriptionService.subscribe(runId, 0L, fullReplay);

        assertTrue(fullReplay.completed());
        assertEquals(List.of("run_status", "run_status", "chunk", "terminal"),
                fullReplay.eventTypes());
        ChatRunEvent chunk = fullReplay.events().stream()
                .filter(event -> "chunk".equals(event.getEventType()))
                .findFirst().orElseThrow();
        assertTrue(chunk.getPayload().contains("diagnosis-1"));

        CollectingSink resumedReplay = new CollectingSink();
        subscriptionService.subscribe(runId, chunk.getSeq(), resumedReplay);
        assertTrue(resumedReplay.completed());
        assertEquals(List.of("terminal"), resumedReplay.eventTypes());
    }

    private void assertRewoundBranchRequest(String branchRunId) {
        List<RunRequest> requests = engine.requests();
        assertTrue(requests.size() >= 3);
        RunRequest branch = requests.get(2);
        assertEquals(branchRunId, branch.sessionId());
        assertEquals("snapshot-1", branch.stateSnapshot());
        assertEquals(2, branch.history().size());
        assertEquals("first symptom", assertInstanceOf(UserTurn.class,
                branch.history().get(0)).text());
        assertEquals("diagnosis-1", assertInstanceOf(AssistantTurn.class,
                branch.history().get(1)).text());
        assertTrue(branch.history().stream().noneMatch(this::isRemovedTurn));
    }

    private boolean isRemovedTurn(TurnMessage message) {
        if (message instanceof UserTurn user) {
            return user.text().contains("second symptom");
        }
        if (message instanceof AssistantTurn assistant) {
            return assistant.text().contains("diagnosis-2");
        }
        return false;
    }

    private long messageId(String sessionId, String content) {
        return jdbc.queryForObject("SELECT id FROM chat_message WHERE session_id=? AND content=?",
                Long.class, sessionId, content);
    }

    private int checkpointCount(String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM native_diagnosis_checkpoint "
                + "WHERE session_id=?", Integer.class, sessionId);
    }

    private void awaitTerminal(String runId, ChatRunStatus expected) throws InterruptedException {
        awaitStatus(runId, Set.of(expected));
        ChatRun run = runRepository.findById(ChatRunId.of(runId)).orElseThrow();
        assertEquals(expected, run.getStatus());
    }

    private void awaitStatus(String runId, Set<ChatRunStatus> expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        ChatRunStatus last = null;
        while (System.nanoTime() < deadline) {
            try {
                ChatRun run = runRepository.findById(ChatRunId.of(runId)).orElse(null);
                if (run != null) {
                    last = run.getStatus();
                    if (expected.contains(last)) {
                        return;
                    }
                }
            } catch (DataAccessException transientSqliteContention) {
                // The async writer may briefly hold the shared in-memory SQLite table lock.
                // Polling is observational, so retry until the same bounded deadline.
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("run " + runId + " did not reach " + expected
                + "; last status=" + last);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NativeFlowConfiguration {

        @Bean
        ScriptedDiagnoseEngine scriptedDiagnoseEngine() {
            return new ScriptedDiagnoseEngine();
        }

        @Bean
        NativeDiagnosisHistoryMapper nativeDiagnosisHistoryMapper(
                StreamOutputExtractor outputExtractor) {
            return new NativeDiagnosisHistoryMapper(outputExtractor);
        }

        @Bean
        NativeRunSummaryMapper nativeRunSummaryMapper() {
            return new NativeRunSummaryMapper();
        }

        @Bean
        NativeDiagnosisAgentRuntime nativeDiagnosisAgentRuntime(
                ScriptedDiagnoseEngine engine,
                NativeDiagnosisProperties properties,
                DiagnosisCheckpointRepository checkpoints,
                NativeDiagnosisHistoryMapper historyMapper,
                NativeRunSummaryMapper summaryMapper) {
            return new NativeDiagnosisAgentRuntime(engine, properties, checkpoints,
                    historyMapper, summaryMapper);
        }

        @Bean
        NativeRuntimeRegistration nativeRuntimeRegistration(
                NativeDiagnosisAgentRuntime runtime) {
            return new NativeRuntimeRegistration("test");
        }
    }

    static final class ScriptedDiagnoseEngine implements DiagnoseEngine {

        private final AtomicInteger turn = new AtomicInteger();
        private final List<RunRequest> requests = new CopyOnWriteArrayList<RunRequest>();
        private final List<String> stoppedRunIds = new CopyOnWriteArrayList<String>();
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final AtomicReference<String> blockedRunId = new AtomicReference<String>();
        private final CountDownLatch blockedRunStarted = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public void run(RunRequest request, Consumer<String> onChunk,
                        Consumer<RunSummary> onComplete) {
            requests.add(request);
            int currentTurn = turn.incrementAndGet();
            if (blockNext.compareAndSet(true, false)) {
                blockedRunId.set(request.sessionId());
                blockedRunStarted.countDown();
                awaitStop(onComplete);
                return;
            }
            onChunk.accept(assistantLine("diagnosis-" + currentTurn));
            onComplete.accept(new RunSummary(ExitReason.SUCCESS,
                    "snapshot-" + currentTurn,
                    new RunSummary.Usage(100L + currentTurn, 20L + currentTurn, currentTurn),
                    ""));
        }

        @Override
        public void stop(String sessionId) {
            stoppedRunIds.add(sessionId);
            if (sessionId.equals(blockedRunId.get())) {
                stopped.countDown();
            }
        }

        @Override
        public boolean isRunning(String sessionId) {
            return sessionId.equals(blockedRunId.get()) && stopped.getCount() > 0L;
        }

        @Override
        public void close() {
            stopped.countDown();
        }

        void blockNextRunUntilStopped() {
            blockNext.set(true);
        }

        boolean awaitBlockedRunStarted(Duration timeout) throws InterruptedException {
            return blockedRunStarted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<RunRequest> requests() {
            return List.copyOf(requests);
        }

        List<String> stoppedRunIds() {
            return List.copyOf(stoppedRunIds);
        }

        private void awaitStop(Consumer<RunSummary> onComplete) {
            try {
                if (!stopped.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    onComplete.accept(new RunSummary(ExitReason.TIMEOUT, "",
                            RunSummary.Usage.zero(), "test stop timeout"));
                    return;
                }
                onComplete.accept(new RunSummary(ExitReason.STOPPED, "",
                        RunSummary.Usage.zero(), ""));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                onComplete.accept(new RunSummary(ExitReason.ERROR, "",
                        RunSummary.Usage.zero(), "test engine interrupted"));
            }
        }

        private String assistantLine(String text) {
            JsonNode message = JSON.createObjectNode()
                    .put("type", "assistant")
                    .set("message", JSON.createObjectNode()
                            .set("content", JSON.createArrayNode()
                                    .add(JSON.createObjectNode()
                                            .put("type", "text")
                                            .put("text", text))));
            return message.toString();
        }
    }

    static final class CollectingSink implements ChatRunStreamSink {

        private final List<ChatRunEvent> events = new CopyOnWriteArrayList<ChatRunEvent>();
        private final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void send(ChatRunEvent event) {
            events.add(event);
        }

        @Override
        public void ping() {
            // A terminal replay closes before a heartbeat is scheduled.
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        @Override
        public void fail(Throwable error) {
            throw new AssertionError("unexpected replay failure", error);
        }

        List<ChatRunEvent> events() {
            return List.copyOf(events);
        }

        List<String> eventTypes() {
            return events.stream().map(ChatRunEvent::getEventType).toList();
        }

        boolean completed() {
            return completed.get();
        }
    }
}
