package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.ExecutionPlanProviderRegistry;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeTermination;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 公共 Runtime 启动、Handle 绑定、回调 fencing 和终态闭环测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatRunRuntimeLauncherTest {

    private static final Instant NOW = Instant.parse("2026-08-01T16:00:00Z");
    private static final ChatRunId RUN_ID = ChatRunId.of("run-runtime-1");
    private static final RuntimeHandle HANDLE =
            new RuntimeHandle("run-runtime-1", "handle-runtime-1");

    private ChatRunRepository runRepository;
    private ExecutionPlanProviderRegistry planProviderRegistry;
    private AgentExecutionGateway executionGateway;
    private ChatRunRuntimeHandleStore handleStore;
    private ChatRunLifecycleService lifecycleService;
    private ChatRunRuntimeTerminationReconciler terminationReconciler;
    private AgentExecutionPlan plan;
    private ChatRun run;
    private ChatRunRuntimeLauncher launcher;

    @BeforeEach
    void setUp() {
        runRepository = mock(ChatRunRepository.class);
        planProviderRegistry = mock(ExecutionPlanProviderRegistry.class);
        executionGateway = mock(AgentExecutionGateway.class);
        handleStore = mock(ChatRunRuntimeHandleStore.class);
        lifecycleService = mock(ChatRunLifecycleService.class);
        terminationReconciler = mock(
                ChatRunRuntimeTerminationReconciler.class);
        plan = mock(AgentExecutionPlan.class);
        run = ChatRun.submit(RUN_ID, "session-runtime-1", 11L,
                "runtime-key", NOW.minusSeconds(2));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(planProviderRegistry.prepare(run)).thenReturn(plan);
        launcher = new ChatRunRuntimeLauncher(runRepository, planProviderRegistry,
                executionGateway, handleStore, lifecycleService,
                terminationReconciler,
                Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
    }

    @Test
    void launchShouldDeferRunLookupAndRuntimeStartToExecutor() {
        AtomicReference<Runnable> scheduled =
                new AtomicReference<Runnable>();
        Executor deferred = scheduled::set;
        ChatRunRuntimeLauncher deferredLauncher =
                new ChatRunRuntimeLauncher(
                        runRepository, planProviderRegistry,
                        executionGateway, handleStore, lifecycleService,
                        terminationReconciler,
                        Clock.fixed(NOW, ZoneOffset.UTC), deferred);
        when(executionGateway.start(
                eq(plan), any(RuntimeEventSink.class))).thenReturn(HANDLE);

        deferredLauncher.launch(RUN_ID);

        assertNotNull(scheduled.get());
        verifyNoInteractions(
                runRepository, planProviderRegistry, executionGateway,
                handleStore, lifecycleService, terminationReconciler);

        scheduled.get().run();

        verify(runRepository, times(2)).findById(RUN_ID);
        verify(planProviderRegistry).prepare(run);
        verify(executionGateway).start(
                eq(plan), any(RuntimeEventSink.class));
        verify(handleStore).bind(RUN_ID, HANDLE, NOW);
    }

    @Test
    void synchronousStartedCallbackShouldPersistOnlyAfterHandleBinding() {
        when(handleStore.find(RUN_ID)).thenReturn(Optional.of(HANDLE));
        doAnswer(invocation -> {
            RuntimeEventSink sink = invocation.getArgument(1);
            sink.onEvent(event(1L, RuntimeEventType.STARTED, "runtime started"));
            return HANDLE;
        }).when(executionGateway).start(eq(plan), any(RuntimeEventSink.class));

        launcher.launch(RUN_ID);

        InOrder order = inOrder(runRepository, planProviderRegistry, executionGateway,
                handleStore, lifecycleService);
        order.verify(runRepository).findById(RUN_ID);
        order.verify(planProviderRegistry).prepare(run);
        order.verify(executionGateway).start(eq(plan), any(RuntimeEventSink.class));
        order.verify(handleStore).bind(RUN_ID, HANDLE, NOW);
        order.verify(handleStore).find(RUN_ID);
        order.verify(lifecycleService).start(RUN_ID);
        order.verify(lifecycleService).append(eq(RUN_ID), eq("runtime_started"), any(String.class));
    }

    @Test
    void bindFailureShouldRejectBufferedCallbackStopHandleAndFailSafely() {
        doAnswer(invocation -> {
            RuntimeEventSink sink = invocation.getArgument(1);
            sink.onEvent(event(1L, RuntimeEventType.STARTED, "runtime started"));
            return HANDLE;
        }).when(executionGateway).start(eq(plan), any(RuntimeEventSink.class));
        doThrow(new IllegalStateException("duplicate runtime handle"))
                .when(handleStore).bind(RUN_ID, HANDLE, NOW);

        launcher.launch(RUN_ID);

        InOrder order = inOrder(handleStore, executionGateway, lifecycleService);
        order.verify(handleStore).bind(RUN_ID, HANDLE, NOW);
        order.verify(executionGateway).requestStop(HANDLE);
        order.verify(lifecycleService).fail(RUN_ID, "RUNTIME_HANDLE_BIND_FAILED",
                "Runtime 状态绑定失败，任务已停止", null);
        verify(lifecycleService, never()).start(any(ChatRunId.class));
        verify(lifecycleService, never()).append(any(ChatRunId.class), any(String.class),
                any(String.class));
    }

    @Test
    void cancellationBeforeHandleBindingShouldStopNewlyBoundRuntime() {
        doAnswer(invocation -> {
            run.requestCancellation(NOW);
            return HANDLE;
        }).when(executionGateway).start(eq(plan), any(RuntimeEventSink.class));

        launcher.launch(RUN_ID);

        verify(handleStore).bind(RUN_ID, HANDLE, NOW);
        verify(executionGateway).requestStop(HANDLE);
    }

    @Test
    void callbackFromStaleHandleShouldBeDiscardedWithoutPersistingOrObserving() {
        AtomicReference<RuntimeEventSink> sinkReference = new AtomicReference<RuntimeEventSink>();
        RuntimeHandle current = new RuntimeHandle("run-runtime-1", "handle-runtime-current");
        captureSinkAndReturnHandle(sinkReference);
        when(handleStore.find(RUN_ID)).thenReturn(Optional.of(current));
        launcher.launch(RUN_ID);

        sinkReference.get().onEvent(event(2L, RuntimeEventType.OUTPUT, "stale output"));
        sinkReference.get().onEvent(event(3L, RuntimeEventType.TERMINATED, "stale terminal"));

        verifyNoInteractions(lifecycleService);
        verify(executionGateway, never()).observe(any(RuntimeHandle.class));
    }

    @Test
    void matchingOutputShouldBecomeRecoverableRuntimeEventWithSafeMetadata() throws Exception {
        AtomicReference<RuntimeEventSink> sinkReference = new AtomicReference<RuntimeEventSink>();
        captureSinkAndReturnHandle(sinkReference);
        when(handleStore.find(RUN_ID)).thenReturn(Optional.of(HANDLE));
        launcher.launch(RUN_ID);

        sinkReference.get().onEvent(event(2L, RuntimeEventType.OUTPUT, "safe output"));

        verify(lifecycleService, never()).append(
                eq(RUN_ID), any(String.class), any(String.class));
        verify(executionGateway, never()).observe(any(RuntimeHandle.class));
    }

    @Test
    void normalizedAssistantOutputShouldPersistRenderableAgentChunk()
            throws Exception {
        AtomicReference<RuntimeEventSink> sinkReference =
                new AtomicReference<RuntimeEventSink>();
        captureSinkAndReturnHandle(sinkReference);
        when(handleStore.find(RUN_ID)).thenReturn(Optional.of(HANDLE));
        launcher.launch(RUN_ID);

        sinkReference.get().onEvent(new RuntimeEvent(
                RUN_ID.getValue(), 2L, RuntimeEventType.OUTPUT,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"type\":\"agent_message\",\"text\":\"answer\"}}",
                "answer"));

        ArgumentCaptor<String> chunkPayload =
                ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).append(
                eq(RUN_ID), eq("agent_chunk"), chunkPayload.capture());
        JsonNode chunk = new ObjectMapper().readTree(
                chunkPayload.getValue());
        assertEquals(2L, chunk.get("runtimeSequence").asLong());
        assertEquals("answer", chunk.get("content").asText());
    }

    @Test
    void structuredRuntimeSemanticsShouldPersistInDeclaredOrder()
            throws Exception {
        AtomicReference<RuntimeEventSink> sinkReference =
                new AtomicReference<RuntimeEventSink>();
        captureSinkAndReturnHandle(sinkReference);
        when(handleStore.find(RUN_ID)).thenReturn(Optional.of(HANDLE));
        launcher.launch(RUN_ID);
        RuntimeEvent runtimeEvent = new RuntimeEvent(
                RUN_ID.getValue(), 7L, RuntimeEventType.OUTPUT,
                "codex event received: item.started", null,
                Arrays.asList(
                        RuntimeSemanticEvent.toolStarted(
                                "shell", "item-7", "RUNNING"),
                        RuntimeSemanticEvent.commandStarted(
                                "service-a", "TEST")));

        sinkReference.get().onEvent(runtimeEvent);

        InOrder order = inOrder(lifecycleService);
        order.verify(lifecycleService).append(
                eq(RUN_ID), eq("tool_started"), any(String.class));
        order.verify(lifecycleService).append(
                eq(RUN_ID), eq("command_started"), any(String.class));
    }

    @Test
    void completedTerminationShouldPersistOutputAndTerminalBeforeUnifiedReconciliation() {
        AtomicReference<RuntimeEventSink> sinkReference = new AtomicReference<RuntimeEventSink>();
        captureSinkAndReturnHandle(sinkReference);
        when(handleStore.find(RUN_ID)).thenReturn(Optional.of(HANDLE));
        when(executionGateway.observe(HANDLE)).thenReturn(RuntimeObservation.terminated(
                HANDLE, 0, RuntimeTerminationReason.COMPLETED, 128L));
        launcher.launch(RUN_ID);

        sinkReference.get().onEvent(event(1L, RuntimeEventType.STARTED, "runtime started"));
        sinkReference.get().onEvent(event(2L, RuntimeEventType.OUTPUT, "first output"));
        sinkReference.get().onEvent(event(3L, RuntimeEventType.OUTPUT, "second output"));
        sinkReference.get().onEvent(event(4L, RuntimeEventType.TERMINATED, "runtime completed"));

        RuntimeTermination termination = new RuntimeTermination(
                0, RuntimeTerminationReason.COMPLETED);
        InOrder order = inOrder(
                lifecycleService, executionGateway, terminationReconciler);
        order.verify(lifecycleService).start(RUN_ID);
        order.verify(lifecycleService).append(eq(RUN_ID), eq("runtime_started"), any(String.class));
        order.verify(lifecycleService).append(eq(RUN_ID), eq("runtime_terminated"), any(String.class));
        order.verify(executionGateway).observe(HANDLE);
        order.verify(terminationReconciler).reconcile(
                RUN_ID, HANDLE, termination);
        verify(lifecycleService, never()).complete(
                any(ChatRunId.class), any(String.class),
                any(Integer.class), any(String.class));
        verify(lifecycleService, never()).fail(
                any(ChatRunId.class), any(String.class),
                any(String.class), any());
    }

    @Test
    void requestedStopTerminationShouldUseSameUnifiedReconciler() {
        AtomicReference<RuntimeEventSink> sinkReference = new AtomicReference<RuntimeEventSink>();
        captureSinkAndReturnHandle(sinkReference);
        when(handleStore.find(RUN_ID)).thenReturn(Optional.of(HANDLE));
        when(executionGateway.observe(HANDLE)).thenReturn(RuntimeObservation.terminated(
                HANDLE, 143, RuntimeTerminationReason.REQUESTED_STOP, 0L));
        launcher.launch(RUN_ID);

        sinkReference.get().onEvent(event(1L, RuntimeEventType.TERMINATED, "stop completed"));

        verify(lifecycleService).append(eq(RUN_ID), eq("runtime_terminated"), any(String.class));
        verify(terminationReconciler).reconcile(
                RUN_ID, HANDLE, new RuntimeTermination(
                        143, RuntimeTerminationReason.REQUESTED_STOP));
        verify(lifecycleService, never()).complete(
                any(ChatRunId.class), any(String.class),
                any(Integer.class), any(String.class));
        verify(lifecycleService, never()).fail(any(ChatRunId.class), any(String.class),
                any(String.class), any());
    }

    @Test
    void allTechnicalTerminationReasonsShouldUseSameUnifiedReconciler() {
        assertUnifiedReconciliation(RuntimeTerminationReason.TIMEOUT, 124);
        assertUnifiedReconciliation(RuntimeTerminationReason.OUTPUT_LIMIT, 137);
        assertUnifiedReconciliation(RuntimeTerminationReason.START_FAILURE, -1);
        assertUnifiedReconciliation(RuntimeTerminationReason.PROCESS_FAILURE, 17);
    }

    @Test
    void eventWithAnotherExecutionIdShouldBeDiscardedBeforeHandleLookup() {
        AtomicReference<RuntimeEventSink> sinkReference = new AtomicReference<RuntimeEventSink>();
        captureSinkAndReturnHandle(sinkReference);
        launcher.launch(RUN_ID);

        sinkReference.get().onEvent(new RuntimeEvent(
                "another-run", 1L, RuntimeEventType.OUTPUT, "foreign output"));

        verify(handleStore, never()).find(RUN_ID);
        verifyNoInteractions(lifecycleService);
    }

    private void assertUnifiedReconciliation(
            RuntimeTerminationReason reason, int exitCode) {
        ChatRunRepository localRunRepository = mock(ChatRunRepository.class);
        ExecutionPlanProviderRegistry localRegistry = mock(ExecutionPlanProviderRegistry.class);
        AgentExecutionGateway localGateway = mock(AgentExecutionGateway.class);
        ChatRunRuntimeHandleStore localStore = mock(ChatRunRuntimeHandleStore.class);
        ChatRunLifecycleService localLifecycle = mock(ChatRunLifecycleService.class);
        ChatRunRuntimeTerminationReconciler localReconciler = mock(
                ChatRunRuntimeTerminationReconciler.class);
        AgentExecutionPlan localPlan = mock(AgentExecutionPlan.class);
        ChatRun localRun = ChatRun.submit(RUN_ID, "session-runtime-1", 11L,
                "runtime-key", NOW.minusSeconds(2));
        AtomicReference<RuntimeEventSink> sinkReference = new AtomicReference<RuntimeEventSink>();
        when(localRunRepository.findById(RUN_ID)).thenReturn(Optional.of(localRun));
        when(localRegistry.prepare(localRun)).thenReturn(localPlan);
        doAnswer(invocation -> {
            sinkReference.set(invocation.getArgument(1));
            return HANDLE;
        }).when(localGateway).start(eq(localPlan), any(RuntimeEventSink.class));
        when(localStore.find(RUN_ID)).thenReturn(Optional.of(HANDLE));
        when(localGateway.observe(HANDLE)).thenReturn(
                RuntimeObservation.terminated(HANDLE, exitCode, reason, 0L));
        ChatRunRuntimeLauncher localLauncher = new ChatRunRuntimeLauncher(
                localRunRepository, localRegistry, localGateway, localStore,
                localLifecycle, localReconciler,
                Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);

        localLauncher.launch(RUN_ID);
        sinkReference.get().onEvent(event(1L, RuntimeEventType.TERMINATED, "terminated"));

        verify(localLifecycle).append(
                eq(RUN_ID), eq("runtime_terminated"), any(String.class));
        verify(localReconciler).reconcile(
                RUN_ID, HANDLE, new RuntimeTermination(exitCode, reason));
        verify(localLifecycle, never()).fail(
                any(ChatRunId.class), any(String.class),
                any(String.class), any());
        verify(localLifecycle, never()).complete(
                any(ChatRunId.class), any(String.class), any(Integer.class), any(String.class));
    }

    private void captureSinkAndReturnHandle(AtomicReference<RuntimeEventSink> sinkReference) {
        doAnswer(invocation -> {
            sinkReference.set(invocation.getArgument(1));
            return HANDLE;
        }).when(executionGateway).start(eq(plan), any(RuntimeEventSink.class));
    }

    private static RuntimeEvent event(long sequence, RuntimeEventType type, String payload) {
        return new RuntimeEvent(RUN_ID.getValue(), sequence, type, payload);
    }
}
