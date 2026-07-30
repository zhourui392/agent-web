package com.example.agentweb;

import com.anthropic.agentkit.domain.diagnosis.DataSourceBinding;
import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalog;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentRef;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.ServiceRef;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolBackends;
import com.anthropic.agentkit.infrastructure.tools.support.LocalFileLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LocalLogSource;
import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.DiagnoseEngineBuilder;
import com.anthropic.agentkit.interfaces.engine.DiagnosisMode;
import com.anthropic.agentkit.interfaces.engine.ReadinessPolicy;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunStreamSink;
import com.example.agentweb.app.chatrun.ChatRunSubscriptionService;
import com.example.agentweb.config.nativeagent.NativeDiagnosisProperties;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationRepository;
import com.example.agentweb.domain.chatrun.ToolInvocationStatus;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisAgentRuntime;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisHistoryMapper;
import com.example.agentweb.infra.nativeagent.NativeRunSummaryMapper;
import com.example.agentweb.infra.nativeagent.NativeRuntimeRegistration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real AgentKit local-log tool loop through agent-web HTTP, ChatRun and SQLite boundaries.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-30
 */
@SpringBootTest(properties = {
        "agent.fs.roots=/tmp",
        "agent.native.enabled=false",
        "agent.native.bound-environment=test",
        "agent.native.timeout-seconds=30",
        "agent.native.local-logs.enabled=true",
        "agent.native.local-logs.service=agent-web",
        "agent.native.local-logs.data-source-id=local-agent-web-logs",
        "agent.native.local-logs.log-zone=UTC",
        "agent.chat.resumable-stream.flush-interval-ms=1"
})
/**
 * @author alex
 */
@AutoConfigureMockMvc(addFilters = false)
@Import(NativeLocalLogDiagnosisFlowTest.LocalLogFlowConfiguration.class)
@Tag("spring-flow")
@ResourceLock("spring-flow-sqlite")
class NativeLocalLogDiagnosisFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private static final String SECRET_MARKER = "fixture-super-secret";
    private static final Path LOG_ROOT = createLogFixture();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ChatRunRepository runRepository;
    @Autowired
    private ChatRunSubscriptionService subscriptionService;
    @Autowired
    private ToolInvocationRepository toolInvocations;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecordingDiagnoseEngine recordingEngine;
    @Autowired
    private ScriptedLocalDiagnosisLlm scriptedLlm;

    @DynamicPropertySource
    static void localLogRoot(DynamicPropertyRegistry registry) {
        registry.add("agent.native.local-logs.root", () -> LOG_ROOT.toString());
    }

    @Test
    void nativeFlow_shouldQueryRealLocalLogAndPersistEvidenceWithoutSecrets() throws Exception {
        String sessionId = createNativeSession();
        String runId = submit(sessionId);

        ChatRun terminal = awaitTerminal(runId);
        assertEquals(ChatRunStatus.SUCCEEDED, terminal.getStatus(), failureDetails(terminal));

        String replay = replay(runId);
        assertToolProtocol(replay);
        assertPersistedToolInvocation(runId);
        assertCheckpointEvidence(sessionId);
        assertEquals(5, scriptedLlm.requestCount());
        assertFalse(replay.contains(LOG_ROOT.toString()));
        assertFalse(replay.contains(SECRET_MARKER));
    }

    private String createNativeSession() throws Exception {
        Path workspace = Files.createTempDirectory("native-local-log-flow");
        String body = JSON.createObjectNode()
                .put("agentType", "NATIVE")
                .put("workingDir", workspace.toString())
                .put("env", "test")
                .toString();
        String response = mvc.perform(post("/api/chat/session")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("sessionId").asText();
    }

    private String submit(String sessionId) throws Exception {
        String body = JSON.createObjectNode()
                .put("message", "诊断 agent-web 最近两小时的 NATIVE_FIXTURE_ERROR")
                .put("recall", false).toString();
        String response = mvc.perform(post("/api/chat/session/{id}/runs", sessionId)
                        .header("Idempotency-Key", "native-local-log-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("runId").asText();
    }

    private void assertToolProtocol(String replay) {
        assertTrue(replay.contains("content_block_start"));
        assertTrue(replay.contains("LogQuery"));
        assertTrue(replay.contains("tool_result"));
        assertTrue(replay.contains("local-agent-web-logs"));
        assertTrue(replay.contains("diagnosis_report"), replay);
        assertTrue(replay.contains("\"keyEvidenceIds\":[\"E1\"]"), replay);
    }

    private void assertPersistedToolInvocation(String runId) {
        List<ToolInvocation> values = toolInvocations.findByRunId(runId, 10, 0);
        assertEquals(1, values.size());
        ToolInvocation invocation = values.getFirst();
        assertEquals("LogQuery", invocation.getToolName());
        assertEquals(ToolInvocationStatus.SUCCEEDED, invocation.getStatus());
        assertTrue(invocation.getOutputText().contains("NATIVE_FIXTURE_ERROR"));
        assertTrue(invocation.getOutputText().contains("apiKey=***"));
        assertFalse(invocation.getOutputText().contains(SECRET_MARKER));
        assertFalse(invocation.getOutputText().contains(LOG_ROOT.toString()));
    }

    private void assertCheckpointEvidence(String sessionId) {
        String snapshot = jdbc.queryForObject(
                "SELECT state_snapshot FROM native_diagnosis_checkpoint WHERE session_id=?",
                String.class, sessionId);
        DiagnosisCase restored = new DiagnosisStateCodec().decode(snapshot).orElseThrow();
        assertEquals(1, restored.ledger().all().size());
        assertEquals("local-log-1", restored.ledger().all().getFirst().toolUseId());
        com.anthropic.agentkit.domain.diagnosis.Evidence evidence =
                restored.ledger().all().getFirst();
        assertTrue(evidence.rawExcerpt().contains("NATIVE_FIXTURE_ERROR"));
        assertTrue(evidence.rawExcerpt().contains("apiKey=***"));
        assertEquals("local-agent-web-logs",
                evidence.metadata().get("diagnosis.dataSourceId"));
        assertFalse(snapshot.contains(SECRET_MARKER));
        assertFalse(snapshot.contains(LOG_ROOT.toString()));
    }

    private String replay(String runId) {
        CollectingSink sink = new CollectingSink();
        subscriptionService.subscribe(runId, 0L, sink);
        assertTrue(sink.completed);
        return sink.events.stream().map(ChatRunEvent::getPayload)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private ChatRun awaitTerminal(String runId) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ChatRun run = runRepository.findById(ChatRunId.of(runId)).orElse(null);
                if (run != null && run.getStatus().isTerminal()) {
                    return run;
                }
            } catch (DataAccessException transientSqliteContention) {
                // The async writer can briefly hold the shared in-memory SQLite table lock.
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("run did not reach terminal status: " + runId);
    }

    private String failureDetails(ChatRun run) {
        StringBuilder detail = new StringBuilder(
                run.getFailureCode() + ": " + run.getErrorMessage());
        if (recordingEngine.failure != null) {
            detail.append('\n').append(recordingEngine.failure);
            for (StackTraceElement element : recordingEngine.failure.getStackTrace()) {
                detail.append("\n\tat ").append(element);
            }
        }
        if (recordingEngine.summary != null) {
            detail.append("\nsummary=").append(recordingEngine.summary);
        }
        return detail.toString();
    }

    private static Path createLogFixture() {
        try {
            Path root = Files.createTempDirectory("agent-web-local-logs").toAbsolutePath();
            Files.writeString(root.resolve("service.log"),
                    "2026-07-30T01:30:00Z ERROR NATIVE_FIXTURE_ERROR traceId=trace-local "
                            + "apiKey=" + SECRET_MARKER + "\n"
                            + "java.lang.IllegalStateException: fixture failure\n");
            return root;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalLogFlowConfiguration {

        @Bean
        ScriptedLocalDiagnosisLlm scriptedLocalDiagnosisLlm() {
            return new ScriptedLocalDiagnosisLlm();
        }

        @Bean(destroyMethod = "close")
        RecordingDiagnoseEngine localLogDiagnoseEngine(ScriptedLocalDiagnosisLlm llm) {
            Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
            LocalLogSource source = new LocalLogSource(
                    "local-agent-web-logs", LOG_ROOT, Set.of("*.log"), ZoneId.of("UTC"),
                    10, 1000, 1048576L, 4, Duration.ofSeconds(2));
            DiagnosisToolBackends backends = DiagnosisToolBackends.builder()
                    .logQuery(new LocalFileLogQueryClient(source, clock)).build();
            DiagnoseEngine delegate = DiagnoseEngineBuilder.create().llm(llm).toolBackends(backends)
                    .mode(DiagnosisMode.OPERATIONAL).readinessPolicy(ReadinessPolicy.failFast())
                    .resourceCatalog(resourceCatalog()).structuredDiagnosis().build();
            return new RecordingDiagnoseEngine(delegate);
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
                RecordingDiagnoseEngine engine, NativeDiagnosisProperties properties,
                DiagnosisCheckpointRepository checkpoints,
                NativeDiagnosisHistoryMapper historyMapper, NativeRunSummaryMapper summaryMapper) {
            return new NativeDiagnosisAgentRuntime(engine, properties, checkpoints,
                    historyMapper, summaryMapper, Clock.fixed(NOW, ZoneId.of("UTC")));
        }

        @Bean
        NativeRuntimeRegistration nativeRuntimeRegistration(
                NativeDiagnosisAgentRuntime runtime) {
            return new NativeRuntimeRegistration("test");
        }

        private DiagnosisResourceCatalog resourceCatalog() {
            ServiceRef service = new ServiceRef("agent-web", Set.of("web"));
            DataSourceBinding binding = new DataSourceBinding(
                    EnvironmentRef.named("test"), service, "local-agent-web-logs",
                    DataSourceType.LOG, "LogQuery", ReadinessStatus.READY,
                    true, Set.of("query"), Map.of("kind", "local-file"));
            DiagnosisResourceCatalogSnapshot snapshot = new DiagnosisResourceCatalogSnapshot(
                    1L, List.of(service), List.of(binding));
            return () -> snapshot;
        }
    }

    static final class RecordingDiagnoseEngine implements DiagnoseEngine {
        private final DiagnoseEngine delegate;
        private volatile Throwable failure;
        private volatile com.anthropic.agentkit.interfaces.engine.RunSummary summary;

        RecordingDiagnoseEngine(DiagnoseEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run(com.anthropic.agentkit.interfaces.engine.RunRequest request,
                        Consumer<String> onChunk,
                        Consumer<com.anthropic.agentkit.interfaces.engine.RunSummary> onComplete) {
            try {
                delegate.run(request, onChunk, value -> {
                    summary = value;
                    onComplete.accept(value);
                });
            } catch (Throwable thrown) {
                failure = thrown;
                throw thrown;
            }
        }

        @Override
        public void stop(String sessionId) {
            delegate.stop(sessionId);
        }

        @Override
        public boolean isRunning(String sessionId) {
            return delegate.isRunning(sessionId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    static final class ScriptedLocalDiagnosisLlm implements LlmClient {

        private int planCalls;
        private int mainCalls;
        private final List<ChatRequest> requests = new CopyOnWriteArrayList<ChatRequest>();

        @Override
        public synchronized LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            requests.add(request);
            AiMessage response = response(request);
            return LlmCall.start(handler, guarded -> {
                if (!response.text().isEmpty()) {
                    guarded.onPartialText(response.text());
                }
                guarded.onComplete(response);
            });
        }

        private AiMessage response(ChatRequest request) {
            if (request.systemPrompt().contains("update_plan")) {
                planCalls++;
                return tool("plan-" + planCalls, "update_plan", planJson());
            }
            if (request.systemPrompt().contains("submit_report")) {
                return tool("report-1", "submit_report", reportJson());
            }
            mainCalls++;
            if (mainCalls == 1) {
                return tool("local-log-1", "LogQuery", logQueryJson());
            }
            return AiMessage.text("结论：本机日志证据 E1 显示 NATIVE_FIXTURE_ERROR。"
                    + " 数据源 local-agent-web-logs，建议检查 fixture failure 调用链。");
        }

        private AiMessage tool(String id, String name, String arguments) {
            return new AiMessage("", List.of(new ToolUseRequest(
                    new ToolUseId(id), name, arguments)));
        }

        private String planJson() {
            return """
                    {
                      "problemStatement":"agent-web 最近两小时出现 NATIVE_FIXTURE_ERROR",
                      "hypotheses":[{"id":"H1","statement":"fixture failure","confidence":0.7}],
                      "steps":[{"id":"S1","goal":"查询本机日志","hypothesisId":"H1",
                        "allowedTools":["LogQuery"],"status":"RUNNING","resultSummary":""}],
                      "missingInputs":[],
                      "scope":{"environment":"test","services":["agent-web"],
                        "timeWindow":{"startInclusive":"2026-07-30T00:00:00Z",
                        "endExclusive":"2026-07-30T02:00:00Z"},"identifiers":{},"tags":{}},
                      "blockers":[]
                    }
                    """;
        }

        private String logQueryJson() {
            return """
                    {"keyword":"NATIVE_FIXTURE_ERROR","service":"agent-web",
                     "startTime":"2026-07-30T00:00:00Z",
                     "endTime":"2026-07-30T02:00:00Z","level":"ERROR","limit":20}
                    """;
        }

        private String reportJson() {
            return """
                    {"summary":"local-agent-web-logs 命中 fixture failure",
                     "rootCauseCandidates":[{"hypothesisId":"H1","summary":"fixture failure",
                       "evidenceIds":["E1"],"confidence":0.9,"confirmed":true}],
                     "keyEvidenceIds":["E1"],"recommendedActions":["检查异常调用链"],
                     "missingInformation":[],"confidence":0.9,"needHumanCheck":false}
                    """;
        }

        private int requestCount() {
            return requests.size();
        }
    }

    private static final class CollectingSink implements ChatRunStreamSink {
        private final List<ChatRunEvent> events = new ArrayList<ChatRunEvent>();
        private boolean completed;

        @Override
        public void send(ChatRunEvent event) {
            events.add(event);
        }

        @Override
        public void ping() {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void fail(Throwable error) {
            throw new AssertionError("unexpected replay failure", error);
        }
    }
}
