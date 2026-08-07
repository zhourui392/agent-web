package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.RunRequest;
import com.anthropic.agentkit.interfaces.engine.RunSummary;
import com.anthropic.agentkit.interfaces.engine.DiagnosisReadiness;
import com.anthropic.agentkit.interfaces.engine.DiagnosisMode;
import com.anthropic.agentkit.interfaces.engine.UserTurn;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentHistoryMessage;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.config.nativeagent.NativeDiagnosisProperties;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpoint;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author alex
 * @since 2026-07-29
 */
class NativeDiagnosisAgentRuntimeTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-29T10:15:30Z");

    private DiagnoseEngine engine;
    private DiagnosisCheckpointRepository checkpoints;
    private NativeDiagnosisAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        engine = mock(DiagnoseEngine.class);
        checkpoints = mock(DiagnosisCheckpointRepository.class);
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        properties.setTimeoutSeconds(300L);
        properties.setBoundEnvironment("test");
        properties.getLocalLogs().setEnabled(true);
        properties.getLocalLogs().setService("agent-web");
        properties.getLocalLogs().setLogZone("Asia/Shanghai");
        runtime = new NativeDiagnosisAgentRuntime(engine, properties, checkpoints,
                new NativeDiagnosisHistoryMapper(new StreamOutputExtractor()),
                new NativeRunSummaryMapper(),
                Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));
    }

    @Test
    void run_shouldBuildTypedRequestWithRunScopedStopKeyAndLatestCheckpoint() {
        DiagnosisCheckpoint checkpoint = DiagnosisCheckpoint.record(
                "previous-run", "conversation-1", 1L, 2L, "previous-state", "v1",
                1L, 2L, 0L, Instant.parse("2026-07-29T09:00:00Z"));
        when(checkpoints.findLatestValidBefore("conversation-1", 7L))
                .thenReturn(Optional.of(checkpoint));
        AgentExecutionResult expected = new NativeRunSummaryMapper().map(
                new RunSummary(com.anthropic.agentkit.interfaces.engine.ExitReason.SUCCESS,
                        "next-state", RunSummary.Usage.zero(), ""));
        doAnswer(call -> {
            Consumer<String> chunks = call.getArgument(1);
            Consumer<RunSummary> completion = call.getArgument(2);
            chunks.accept("{\"type\":\"assistant\"}");
            completion.accept(new RunSummary(
                    com.anthropic.agentkit.interfaces.engine.ExitReason.SUCCESS,
                    "next-state", RunSummary.Usage.zero(), ""));
            return null;
        }).when(engine).run(any(RunRequest.class), any(), any());
        AtomicReference<String> chunk = new AtomicReference<String>();
        AtomicReference<AgentExecutionResult> completion =
                new AtomicReference<AgentExecutionResult>();

        runtime.run(invocation(120L), chunk::set, completion::set);

        ArgumentCaptor<RunRequest> request = ArgumentCaptor.forClass(RunRequest.class);
        verify(engine).run(request.capture(), any(), any());
        RunRequest captured = request.getValue();
        assertEquals("/workspace", captured.workingDir());
        assertEquals("current question", captured.userMessage());
        assertEquals("run-7", captured.sessionId());
        assertEquals("test", captured.env());
        assertEquals(FIXED_NOW, captured.operationalContext().now());
        assertEquals(ZoneId.of("Asia/Shanghai"), captured.operationalContext().zoneId());
        assertEquals("test", captured.operationalContext().environment().name());
        assertEquals("agent-web", captured.operationalContext().defaultService());
        assertEquals(ReadinessStatus.READY,
                captured.operationalContext().dataSources().getFirst().readiness());
        assertEquals(120L, captured.timeoutSeconds());
        assertEquals("previous-state", captured.stateSnapshot());
        assertEquals(1, captured.history().size());
        assertEquals("prior question", ((UserTurn) captured.history().get(0)).text());
        assertEquals("{\"type\":\"assistant\"}", chunk.get());
        assertEquals(expected.getStreamResult(), completion.get().getStreamResult());
        assertEquals("next-state", completion.get().getCheckpoint().stateSnapshot());
    }

    @Test
    void runtimeCapabilities_shouldBeNativeTypedAndPassThrough() {
        assertEquals(java.util.Set.of(AgentType.NATIVE), runtime.supportedTypes());
        assertEquals(HistoryDeliveryMode.TYPED, runtime.historyDeliveryMode());
        assertEquals(List.of("raw-line"), runtime.normalizeChunk(AgentType.NATIVE, "raw-line"));
        assertNull(runtime.extractResumeId(AgentType.NATIVE, "raw-line"));

        runtime.stop("run-7");

        verify(engine).stop("run-7");
    }

    @Test
    void configuredTimeout_shouldCapLargerPerCallTimeout() {
        when(checkpoints.findLatestValidBefore("conversation-1", 7L))
                .thenReturn(Optional.empty());
        doAnswer(call -> null).when(engine).run(any(RunRequest.class), any(), any());

        runtime.run(invocation(900L), ignored -> { }, ignored -> { });

        ArgumentCaptor<RunRequest> request = ArgumentCaptor.forClass(RunRequest.class);
        verify(engine).run(request.capture(), any(), any());
        assertEquals(300L, request.getValue().timeoutSeconds());
        assertEquals("", request.getValue().stateSnapshot());
    }

    @Test
    void runWithDifferentEnvironment_shouldFailBeforeCallingBoundEngine() {
        AgentRunInvocation invocation = invocation(120L, "prod");

        assertThrows(IllegalArgumentException.class,
                () -> runtime.run(invocation, ignored -> { }, ignored -> { }));

        org.mockito.Mockito.verifyNoInteractions(engine);
    }

    @Test
    void multipleEnvironmentBindings_shouldRouteToIsolatedEngineAndContext() {
        DiagnoseEngine testEngine = mock(DiagnoseEngine.class);
        DiagnoseEngine prodEngine = mock(DiagnoseEngine.class);
        NativeDiagnosisProperties test = environmentProperties("test", "test-service", "test-ds");
        NativeDiagnosisProperties prod = environmentProperties("prod", "prod-service", "prod-ds");
        runtime = new NativeDiagnosisAgentRuntime(Map.of(
                "test", NativeDiagnosisEnvironmentBinding.from(
                        testEngine, "test", test, Clock.fixed(FIXED_NOW, ZoneId.of("UTC"))),
                "prod", NativeDiagnosisEnvironmentBinding.from(
                        prodEngine, "prod", prod, Clock.fixed(FIXED_NOW, ZoneId.of("UTC")))),
                checkpoints, new NativeDiagnosisHistoryMapper(new StreamOutputExtractor()),
                new NativeRunSummaryMapper());
        when(checkpoints.findLatestValidBefore("conversation-1", 7L))
                .thenReturn(Optional.empty());

        runtime.run(invocation(120L, "prod"), ignored -> { }, ignored -> { });

        ArgumentCaptor<RunRequest> request = ArgumentCaptor.forClass(RunRequest.class);
        verify(prodEngine).run(request.capture(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(testEngine);
        assertEquals("prod", request.getValue().operationalContext().environment().name());
        assertEquals("prod-service", request.getValue().operationalContext().defaultService());
        assertEquals("prod-ds",
                request.getValue().operationalContext().dataSources().getFirst().id());
        assertEquals(Set.of("test", "prod"), runtime.boundEnvironments());
    }

    @Test
    void multipleEnvironmentBindings_shouldRejectMissingAndUnknownEnvironment() {
        DiagnoseEngine prodEngine = mock(DiagnoseEngine.class);
        NativeDiagnosisProperties prod = environmentProperties("prod", "prod-service", "prod-ds");
        runtime = new NativeDiagnosisAgentRuntime(Map.of(
                "prod", NativeDiagnosisEnvironmentBinding.from(
                        prodEngine, "prod", prod, Clock.fixed(FIXED_NOW, ZoneId.of("UTC"))),
                "test", NativeDiagnosisEnvironmentBinding.from(
                        mock(DiagnoseEngine.class), "test",
                        environmentProperties("test", "test-service", "test-ds"),
                        Clock.fixed(FIXED_NOW, ZoneId.of("UTC")))),
                checkpoints, new NativeDiagnosisHistoryMapper(new StreamOutputExtractor()),
                new NativeRunSummaryMapper());

        assertThrows(IllegalArgumentException.class,
                () -> runtime.run(invocation(120L, ""), ignored -> { }, ignored -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.run(invocation(120L, "staging"), ignored -> { }, ignored -> { }));
        org.mockito.Mockito.verifyNoInteractions(prodEngine);
    }

    @Test
    void commonStart_shouldRouteByFrozenProfileIdToProfileEngine() throws Exception {
        DiagnoseEngine testEngine = mock(DiagnoseEngine.class);
        DiagnoseEngine profileEngine = mock(DiagnoseEngine.class);
        DiagnoseEngine overrideEngine = mock(DiagnoseEngine.class);
        NativeDiagnosisProperties test = environmentProperties("test", "test-service", "test-ds");
        NativeDiagnosisProperties prod = environmentProperties("prod", "prod-service", "prod-ds");
        NativeDiagnosisEnvironmentBinding testBinding = NativeDiagnosisEnvironmentBinding.from(
                testEngine, "test", test, Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));
        NativeDiagnosisEnvironmentBinding prodBinding = NativeDiagnosisEnvironmentBinding.from(
                profileEngine, "prod", prod, Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));
        NativeDiagnosisEnvironmentBinding overrideBinding = NativeDiagnosisEnvironmentBinding.from(
                overrideEngine, "prod", prod, Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));
        runtime = new NativeDiagnosisAgentRuntime(
                Map.of("test", testBinding, "prod", prodBinding),
                Map.of("native-prod", prodBinding),
                Map.of("native-prod", Map.of("profile-model", prodBinding,
                        "profile-override", overrideBinding)), checkpoints,
                new NativeDiagnosisHistoryMapper(new StreamOutputExtractor()),
                new NativeRunSummaryMapper(), null);
        when(checkpoints.findLatestValidBefore("conversation-profile", 8L))
                .thenReturn(Optional.empty());
        doAnswer(call -> {
            Consumer<com.anthropic.agentkit.interfaces.engine.RunSummary> completion =
                    call.getArgument(2);
            completion.accept(new RunSummary(
                    com.anthropic.agentkit.interfaces.engine.ExitReason.SUCCESS,
                    "", RunSummary.Usage.zero(), ""));
            return null;
        }).when(overrideEngine).run(any(RunRequest.class), any(), any());
        AgentExecutionPlan plan = org.mockito.Mockito.mock(AgentExecutionPlan.class);
        when(plan.getExecutionIdentity()).thenReturn(new ExecutionIdentity(
                "profile-run", "owner-1", "chat:profile", "conversation-profile", 8L));
        when(plan.getRuntimeSelection()).thenReturn(new RuntimeSelection(
                "native-prod", AgentType.NATIVE, "https://profile.example", "profile-override",
                "high", "prod", RuntimeVersionPolicy.configured()));
        when(plan.getPromptPayload()).thenReturn(new PromptPayload(
                "profile question", CanonicalHashing.sha256("profile question"),
                com.example.agentweb.app.runtime.port.HistoryDelivery.TYPED));
        when(plan.getWorkspaceLayout()).thenReturn(new WorkspaceLayout(
                "/workspace", "/workspace", new ArrayList<String>(List.of("/workspace")),
                new ArrayList<String>(List.of("/workspace")), SandboxMode.WORKSPACE_WRITE));
        when(plan.getRuntimeLimits()).thenReturn(new RuntimeLimits(
                java.time.Duration.ofSeconds(30L), 1024L));
        CountDownLatch terminal = new CountDownLatch(1);
        RuntimeEventSink sink = event -> {
            if (event.getType() == com.example.agentweb.app.runtime.port.RuntimeEventType.TERMINATED) {
                terminal.countDown();
            }
        };

        runtime.start(plan, sink);

        assertTrue(terminal.await(2L, TimeUnit.SECONDS));
        verify(overrideEngine).run(any(RunRequest.class), any(), any());
        org.mockito.Mockito.verifyNoInteractions(profileEngine);
        org.mockito.Mockito.verifyNoInteractions(testEngine);
    }

    @Test
    void remoteLogBinding_shouldPopulateHostOwnedOperationalScope() {
        DiagnoseEngine remoteEngine = mock(DiagnoseEngine.class);
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        properties.setBoundEnvironment("prod");
        properties.setTimezone("Asia/Shanghai");
        properties.getBackends().setLogQueryUrl("https://logs.example/query");
        properties.getBackends().setLogQueryService("orders-api");
        properties.getBackends().setLogQueryDataSourceId("prod-orders-logs");
        runtime = new NativeDiagnosisAgentRuntime(Map.of(
                "prod", NativeDiagnosisEnvironmentBinding.from(
                        remoteEngine, "prod", properties,
                        Clock.fixed(FIXED_NOW, ZoneId.of("UTC")))),
                checkpoints, new NativeDiagnosisHistoryMapper(new StreamOutputExtractor()),
                new NativeRunSummaryMapper());
        when(checkpoints.findLatestValidBefore("conversation-1", 7L))
                .thenReturn(Optional.empty());

        runtime.run(invocation(120L, "prod"), ignored -> { }, ignored -> { });

        ArgumentCaptor<RunRequest> request = ArgumentCaptor.forClass(RunRequest.class);
        verify(remoteEngine).run(request.capture(), any(), any());
        assertEquals("orders-api", request.getValue().operationalContext().defaultService());
        assertEquals("prod-orders-logs",
                request.getValue().operationalContext().dataSources().getFirst().id());
        assertEquals(ZoneId.of("Asia/Shanghai"),
                request.getValue().operationalContext().zoneId());
    }

    @Test
    void readinessProjection_shouldExposeOnlyLogicalNonSecretFacts() {
        when(engine.readiness()).thenReturn(new DiagnosisReadiness(
                ReadinessStatus.READY, DiagnosisMode.OPERATIONAL,
                List.of(new com.anthropic.agentkit.interfaces.engine.DiagnosisCapability(
                        "LogQuery", "test-logs", "test", ReadinessStatus.READY,
                        Set.of("query"), "")), ""));

        var readiness = runtime.currentReadiness().getFirst();

        assertEquals("test", readiness.environment());
        assertEquals("CONFIGURED", readiness.modelStatus());
        assertEquals("OPERATIONAL", readiness.diagnosisMode());
        assertEquals("READY", readiness.overallStatus());
        assertEquals("test-logs", readiness.capabilities().getFirst().dataSourceId());
        assertFalse(readiness.toString().contains("api-key"));
    }

    @Test
    void operationalContext_shouldProjectUnavailableBackendInsteadOfConfiguredAsReady() {
        when(engine.readiness()).thenReturn(new DiagnosisReadiness(
                ReadinessStatus.UNAVAILABLE, DiagnosisMode.OPERATIONAL,
                List.of(new com.anthropic.agentkit.interfaces.engine.DiagnosisCapability(
                        "LogQuery", "local-agent-web-logs", "test",
                        ReadinessStatus.UNAVAILABLE, Set.of("query"),
                        "BACKEND_CONNECTION_FAILED")), "BACKEND_CONNECTION_FAILED"));
        when(checkpoints.findLatestValidBefore("conversation-1", 7L))
                .thenReturn(Optional.empty());
        doAnswer(call -> null).when(engine).run(any(RunRequest.class), any(), any());

        runtime.run(invocation(120L), ignored -> { }, ignored -> { });

        ArgumentCaptor<RunRequest> request = ArgumentCaptor.forClass(RunRequest.class);
        verify(engine).run(request.capture(), any(), any());
        assertEquals(ReadinessStatus.UNAVAILABLE,
                request.getValue().operationalContext().dataSources().getFirst().readiness());
        assertEquals(Set.of(), runtime.operationalEnvironments());
    }

    private NativeDiagnosisProperties environmentProperties(
            String environment, String service, String dataSource) {
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        properties.setBoundEnvironment(environment);
        properties.setTimeoutSeconds(300L);
        properties.getLocalLogs().setEnabled(true);
        properties.getLocalLogs().setService(service);
        properties.getLocalLogs().setDataSourceId(dataSource);
        properties.getLocalLogs().setLogZone("UTC");
        return properties;
    }

    private AgentRunInvocation invocation(long timeoutSeconds) {
        return invocation(timeoutSeconds, "test");
    }

    private AgentRunInvocation invocation(long timeoutSeconds, String environment) {
        return AgentRunInvocation.builder()
                .runId("run-7")
                .conversationId("conversation-1")
                .userMessageId(7L)
                .agentType(AgentType.NATIVE)
                .workingDir("/workspace")
                .prompt("current question")
                .resumeId("must-not-be-used")
                .env(environment)
                .userId("user-1")
                .timeoutSeconds(timeoutSeconds)
                .history(List.of(new AgentHistoryMessage("user", "prior question")))
                .build();
    }
}
