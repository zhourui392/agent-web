package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.RunRequest;
import com.anthropic.agentkit.interfaces.engine.RunSummary;
import com.anthropic.agentkit.interfaces.engine.DiagnosisReadiness;
import com.anthropic.agentkit.interfaces.engine.DiagnosisMode;
import com.anthropic.agentkit.interfaces.engine.UserTurn;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.agentrun.port.AgentHistoryMessage;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    void nativeExecution_shouldNeverExposeStopRequestedAfterConcurrentTermination()
            throws Exception {
        Class<?> executionType = Class.forName(
                "com.example.agentweb.infra.nativeagent.NativeDiagnosisAgentRuntime$NativeExecution");
        java.lang.reflect.Constructor<?> constructor = executionType
                .getDeclaredConstructor(RuntimeHandle.class, DiagnoseEngine.class,
                        RuntimeEventSink.class);
        constructor.setAccessible(true);
        java.lang.reflect.Method requestStop = executionType
                .getDeclaredMethod("requestStop");
        java.lang.reflect.Method terminate = executionType.getDeclaredMethod(
                "terminate", int.class, RuntimeTerminationReason.class);
        java.lang.reflect.Method observe = executionType.getDeclaredMethod("observe");
        requestStop.setAccessible(true);
        terminate.setAccessible(true);
        observe.setAccessible(true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 2_000; attempt++) {
                // Given
                RuntimeHandle handle = new RuntimeHandle("race-" + attempt,
                        "native-race-" + attempt);
                Object execution = constructor.newInstance(handle, engine,
                        (RuntimeEventSink) event -> { });
                CyclicBarrier barrier = new CyclicBarrier(3);

                // When
                executor.submit(() -> invokeAtBarrier(requestStop, execution, barrier));
                executor.submit(() -> invokeAtBarrier(terminate, execution, barrier,
                        -1, RuntimeTerminationReason.COMPLETED));
                barrier.await(5L, TimeUnit.SECONDS);
                barrier.await(5L, TimeUnit.SECONDS);

                // Then
                RuntimeObservation observation = (RuntimeObservation) observe.invoke(execution);
                assertEquals(RuntimeState.TERMINATED, observation.getState(),
                        "terminal execution must not regress to STOP_REQUESTED");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void invokeAtBarrier(java.lang.reflect.Method method, Object target,
                                 CyclicBarrier barrier, Object... arguments) {
        try {
            barrier.await(5L, TimeUnit.SECONDS);
            method.invoke(target, arguments);
            barrier.await(5L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
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
        stubSuccessfulRun(remoteEngine);
        when(checkpoints.findLatestValidBefore(any(), anyLong()))
                .thenReturn(Optional.empty());

        AgentExecutionPlan plan = plan("remote-run", "prod", "/workspace");
        startAndAwait(plan);

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
        when(checkpoints.findLatestValidBefore(any(), anyLong()))
                .thenReturn(Optional.empty());
        stubSuccessfulRun(engine);

        AgentExecutionPlan plan = plan("readiness-run", "test", "/workspace");
        startAndAwait(plan);

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

    private AgentExecutionPlan plan(String executionId, String environment,
                                    String workspace) {
        AgentExecutionPlan plan = org.mockito.Mockito.mock(AgentExecutionPlan.class);
        when(plan.getExecutionIdentity()).thenReturn(new ExecutionIdentity(
                executionId, "owner-1", "chat:" + executionId,
                "conversation-" + executionId, 7L));
        when(plan.getRuntimeSelection()).thenReturn(new RuntimeSelection(
                null, AgentType.NATIVE, null, null, null, environment,
                RuntimeVersionPolicy.configured()));
        when(plan.getPromptPayload()).thenReturn(new PromptPayload(
                "runtime question", CanonicalHashing.sha256("runtime question"),
                com.example.agentweb.app.runtime.port.HistoryDelivery.TYPED));
        when(plan.getWorkspaceLayout()).thenReturn(new WorkspaceLayout(
                workspace, workspace, new ArrayList<String>(List.of(workspace)),
                new ArrayList<String>(List.of(workspace)),
                SandboxMode.WORKSPACE_WRITE));
        when(plan.getRuntimeLimits()).thenReturn(new RuntimeLimits(
                java.time.Duration.ofSeconds(30L), 1024L));
        return plan;
    }

    private void startAndAwait(AgentExecutionPlan plan) {
        CountDownLatch terminal = new CountDownLatch(1);
        runtime.start(plan, event -> {
            if (event.getType()
                    == com.example.agentweb.app.runtime.port.RuntimeEventType.TERMINATED) {
                terminal.countDown();
            }
        });
        try {
            assertTrue(terminal.await(2L, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void stubSuccessfulRun(DiagnoseEngine target) {
        doAnswer(call -> {
            Consumer<RunSummary> completion = call.getArgument(2);
            completion.accept(new RunSummary(
                    com.anthropic.agentkit.interfaces.engine.ExitReason.SUCCESS,
                    "", RunSummary.Usage.zero(), ""));
            return null;
        }).when(target).run(any(RunRequest.class), any(), any());
    }

}
