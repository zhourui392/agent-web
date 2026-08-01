package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeTermination;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryDecision;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RuntimeHandle 驱动的 ChatRun 重启恢复编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatRunRecoveryServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-01T18:30:00Z");
    private static final String RESTART_MESSAGE = "服务重启，任务已中断";

    private ChatRunQueryService queryService;
    private ChatRunRepository runRepository;
    private ChatRunLifecycleService lifecycleService;
    private ChatRunRuntimeHandleStore handleStore;
    private AgentExecutionGateway executionGateway;
    private ChatRunRuntimeTerminationReconciler terminationReconciler;
    private ChatRunRecoveryObserver recoveryObserver;
    private ChatRunRecoveryService service;

    @BeforeEach
    void setUp() {
        queryService = mock(ChatRunQueryService.class);
        runRepository = mock(ChatRunRepository.class);
        lifecycleService = mock(ChatRunLifecycleService.class);
        handleStore = mock(ChatRunRuntimeHandleStore.class);
        executionGateway = mock(AgentExecutionGateway.class);
        terminationReconciler = mock(
                ChatRunRuntimeTerminationReconciler.class);
        recoveryObserver = mock(ChatRunRecoveryObserver.class);
        service = new ChatRunRecoveryService(
                queryService, runRepository, lifecycleService,
                handleStore, executionGateway, terminationReconciler,
                Collections.singletonList(recoveryObserver));
    }

    @Test
    void missingHandleShouldInterruptWithoutReplayingRuntime() {
        ChatRun run = running("run-missing-handle");
        active(run);
        when(handleStore.find(run.getId()))
                .thenReturn(Optional.<RuntimeHandle>empty());

        assertEquals(1, service.interruptOrphans());

        verify(lifecycleService).interrupt(run.getId(), RESTART_MESSAGE);
        verify(recoveryObserver).reconciled(
                run, ChatRunRecoveryDecision.INTERRUPT);
        verifyNoInteractions(executionGateway, terminationReconciler);
    }

    @Test
    void provablyRunningHandleShouldRemainActive() {
        ChatRun run = running("run-alive");
        RuntimeHandle handle = handle(run);
        active(run);
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        when(executionGateway.observe(handle))
                .thenReturn(RuntimeObservation.running(handle, 128L));

        assertEquals(0, service.interruptOrphans());

        verify(executionGateway).observe(handle);
        verify(lifecycleService, never()).interrupt(
                run.getId(), RESTART_MESSAGE);
        verify(handleStore, never()).delete(run.getId());
        verifyNoInteractions(terminationReconciler);
        verify(recoveryObserver).reconciled(
                run, ChatRunRecoveryDecision.RETAIN_ACTIVE);
    }

    @Test
    void terminalHandleShouldReconcileExactFactBeforeDeletingBinding() {
        ChatRun run = running("run-terminal");
        RuntimeHandle handle = handle(run);
        RuntimeTermination termination = new RuntimeTermination(
                0, RuntimeTerminationReason.COMPLETED);
        active(run);
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        when(executionGateway.observe(handle)).thenReturn(
                RuntimeObservation.terminated(
                        handle, termination.getExitCode(),
                        termination.getReason(), 256L));

        assertEquals(1, service.interruptOrphans());

        InOrder order = inOrder(
                executionGateway, terminationReconciler, handleStore);
        order.verify(executionGateway).observe(handle);
        order.verify(terminationReconciler).reconcile(
                run.getId(), handle, termination);
        order.verify(handleStore).delete(run.getId());
        verify(lifecycleService, never()).interrupt(
                run.getId(), RESTART_MESSAGE);
        verify(recoveryObserver).reconciled(
                run, ChatRunRecoveryDecision.FINALIZE_TERMINATION);
    }

    @Test
    void runtimeNotFoundShouldInterruptAndRemoveStaleHandle() {
        ChatRun run = running("run-not-found");
        RuntimeHandle handle = handle(run);
        active(run);
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        when(executionGateway.observe(handle))
                .thenReturn(RuntimeObservation.notFound(handle));

        assertEquals(1, service.interruptOrphans());

        InOrder order = inOrder(
                executionGateway, lifecycleService, handleStore);
        order.verify(executionGateway).observe(handle);
        order.verify(lifecycleService).interrupt(
                run.getId(), RESTART_MESSAGE);
        order.verify(handleStore).delete(run.getId());
        verifyNoInteractions(terminationReconciler);
    }

    @Test
    void pendingRunWithLiveHandleShouldStopThenInterruptWithoutDeletingEvidence() {
        ChatRun run = pending("run-pending-live");
        RuntimeHandle handle = handle(run);
        active(run);
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        when(executionGateway.observe(handle))
                .thenReturn(RuntimeObservation.running(handle, 0L));

        assertEquals(1, service.interruptOrphans());

        InOrder order = inOrder(executionGateway, lifecycleService);
        order.verify(executionGateway).observe(handle);
        order.verify(executionGateway).requestStop(handle);
        order.verify(lifecycleService).interrupt(
                run.getId(), RESTART_MESSAGE);
        verify(handleStore, never()).delete(run.getId());
    }

    @Test
    void observationFailureShouldAttemptStopInterruptAndKeepHandleForAudit() {
        ChatRun run = running("run-observe-failed");
        RuntimeHandle handle = handle(run);
        active(run);
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        when(executionGateway.observe(handle))
                .thenThrow(new IllegalStateException("provider path and stderr"));

        assertEquals(1, service.interruptOrphans());

        InOrder order = inOrder(executionGateway, lifecycleService);
        order.verify(executionGateway).observe(handle);
        order.verify(executionGateway).requestStop(handle);
        order.verify(lifecycleService).interrupt(
                run.getId(), RESTART_MESSAGE);
        verify(handleStore, never()).delete(run.getId());
    }

    @Test
    void oneRecoveryFailureShouldNotPreventLaterRunReconciliation() {
        ChatRun first = running("run-first");
        ChatRun second = running("run-second");
        when(queryService.findActiveRunIds()).thenReturn(
                Arrays.asList(first.getId().getValue(), second.getId().getValue()));
        when(runRepository.findById(first.getId())).thenReturn(Optional.of(first));
        when(runRepository.findById(second.getId())).thenReturn(Optional.of(second));
        when(handleStore.find(first.getId()))
                .thenReturn(Optional.<RuntimeHandle>empty());
        when(handleStore.find(second.getId()))
                .thenReturn(Optional.<RuntimeHandle>empty());
        doThrow(new IllegalStateException("stale"))
                .when(lifecycleService).interrupt(
                        first.getId(), RESTART_MESSAGE);

        assertEquals(1, service.interruptOrphans());

        verify(lifecycleService).interrupt(
                second.getId(), RESTART_MESSAGE);
        verify(recoveryObserver).failed(first);
        verify(recoveryObserver).reconciled(
                second, ChatRunRecoveryDecision.INTERRUPT);
    }

    @Test
    void telemetryFailureShouldNotBlockRecoverySideEffects() {
        ChatRun run = running("run-observer-failed");
        active(run);
        when(handleStore.find(run.getId()))
                .thenReturn(Optional.<RuntimeHandle>empty());
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(recoveryObserver).reconciled(
                        run, ChatRunRecoveryDecision.INTERRUPT);

        assertEquals(1, service.interruptOrphans());

        verify(lifecycleService).interrupt(run.getId(), RESTART_MESSAGE);
    }

    private void active(ChatRun run) {
        when(queryService.findActiveRunIds()).thenReturn(
                Collections.singletonList(run.getId().getValue()));
        when(runRepository.findById(run.getId()))
                .thenReturn(Optional.of(run));
    }

    private static ChatRun running(String runId) {
        ChatRun run = pending(runId);
        run.start(CREATED_AT.plusSeconds(1));
        return run;
    }

    private static ChatRun pending(String runId) {
        return ChatRun.submit(
                ChatRunId.of(runId), "session-" + runId, 1L,
                "key-" + runId, CREATED_AT);
    }

    private static RuntimeHandle handle(ChatRun run) {
        return new RuntimeHandle(
                run.getId().getValue(), "handle-" + run.getId().getValue());
    }
}
