package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.AuthorizedChatRunEventReplayService;
import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.EventCursorExpiredException;
import com.example.agentweb.app.chatrun.ChatRunStreamHandle;
import com.example.agentweb.app.chatrun.ChatRunTerminalFinalizer;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench Run stop commit 顺序与授权后订阅编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunAppServiceTest {

    private WorkbenchRunPreparationService preparationService;
    private WorkbenchRunSubmissionCommitter submissionCommitter;
    private WorkbenchRunAccessResolver accessResolver;
    private ChatRunEventAppender eventAppender;
    private ChatRunTerminalFinalizer terminalFinalizer;
    private ChatRunRuntimeHandleStore handleStore;
    private AgentExecutionGateway executionGateway;
    private AuthorizedChatRunEventReplayService replayService;
    private SafeWorkbenchRunLogger safeLogger;
    private WorkbenchTelemetry telemetry;
    private WorkbenchRunAppService service;

    @BeforeEach
    void setUp() {
        preparationService = mock(WorkbenchRunPreparationService.class);
        submissionCommitter = mock(WorkbenchRunSubmissionCommitter.class);
        accessResolver = mock(WorkbenchRunAccessResolver.class);
        eventAppender = mock(ChatRunEventAppender.class);
        terminalFinalizer = mock(ChatRunTerminalFinalizer.class);
        handleStore = mock(ChatRunRuntimeHandleStore.class);
        executionGateway = mock(AgentExecutionGateway.class);
        replayService = mock(AuthorizedChatRunEventReplayService.class);
        safeLogger = mock(SafeWorkbenchRunLogger.class);
        telemetry = mock(WorkbenchTelemetry.class);
        service = new WorkbenchRunAppService(
                preparationService, submissionCommitter, accessResolver,
                eventAppender, terminalFinalizer, handleStore,
                executionGateway, replayService, safeLogger,
                telemetry,
                Clock.fixed(WorkbenchRunTestFixtures.NOW, ZoneOffset.UTC));
    }

    @Test
    void runningStopShouldPersistIntentBeforeReadingPersistedHandle() {
        ChatRun run = WorkbenchRunTestFixtures.runningRun();
        authorize(run);
        RuntimeHandle handle = new RuntimeHandle("execution-1", "handle-1");
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        ArgumentCaptor<Runnable> afterCommit =
                ArgumentCaptor.forClass(Runnable.class);

        WorkbenchRunStopResult result = service.stop(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1");

        assertEquals(ChatRunStatus.CANCEL_REQUESTED, result.getStatus());
        InOrder persistenceOrder = inOrder(eventAppender);
        persistenceOrder.verify(eventAppender).appendToExistingRun(
                any(), any(), any());
        persistenceOrder.verify(eventAppender).afterCommit(
                afterCommit.capture());
        verifyNoInteractions(handleStore, executionGateway);

        afterCommit.getValue().run();

        verify(handleStore).find(run.getId());
        verify(executionGateway).requestStop(handle);
        assertEquals(ChatRunStatus.CANCEL_REQUESTED, run.getStatus());
    }

    @Test
    void runtimeStopFailureShouldKeepPersistedCancellationIntent() {
        ChatRun run = WorkbenchRunTestFixtures.runningRun();
        authorize(run);
        RuntimeHandle handle = new RuntimeHandle("execution-1", "handle-1");
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        org.mockito.Mockito.doThrow(new IllegalStateException("runtime down"))
                .when(executionGateway).requestStop(handle);
        ArgumentCaptor<Runnable> afterCommit =
                ArgumentCaptor.forClass(Runnable.class);

        WorkbenchRunStopResult result = service.stop(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1");
        verify(eventAppender).afterCommit(afterCommit.capture());

        assertDoesNotThrow(() -> afterCommit.getValue().run());
        assertEquals(ChatRunStatus.CANCEL_REQUESTED, result.getStatus());
        assertEquals(ChatRunStatus.CANCEL_REQUESTED, run.getStatus());
        verify(terminalFinalizer, never()).finalizeFirstTerminal(any(), any());
        verify(safeLogger).runtimeStopFailed(
                "run-1", "IllegalStateException");
    }

    @Test
    void pendingStopShouldUseRealDomainTerminalWithoutExternalStop() {
        ChatRun run = WorkbenchRunTestFixtures.pendingRun();
        authorize(run);

        WorkbenchRunStopResult result = service.stop(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1");

        assertEquals(ChatRunStatus.CANCELLED, result.getStatus());
        verify(terminalFinalizer).finalizeFirstTerminal(
                run, WorkbenchRunTestFixtures.NOW);
        verify(eventAppender, never()).afterCommit(any());
        verifyNoInteractions(handleStore, executionGateway);
    }

    @Test
    void subscriptionShouldAuthorizeBeforeEnteringCommonReplayCore() {
        ChatRun run = WorkbenchRunTestFixtures.runningRun();
        AuthorizedWorkbenchRun authorized = authorize(run);
        WorkbenchRunStreamSink sink = mock(WorkbenchRunStreamSink.class);
        ChatRunStreamHandle commonHandle = mock(ChatRunStreamHandle.class);
        when(replayService.subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(commonHandle);

        service.subscribe(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID,
                "run-1", 7L, sink);

        InOrder order = inOrder(accessResolver, replayService);
        order.verify(accessResolver).requireAuthorized(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1");
        order.verify(replayService).subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(7L), any());
        verify(telemetry).sseReconnect("SUCCESS");
    }

    @Test
    void expiredReconnectShouldRecordStableFailureResult() {
        ChatRun run = WorkbenchRunTestFixtures.runningRun();
        AuthorizedWorkbenchRun authorized = authorize(run);
        WorkbenchRunStreamSink sink = mock(WorkbenchRunStreamSink.class);
        when(replayService.subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenThrow(new EventCursorExpiredException(
                        "run-1", 8L, 20L));

        org.junit.jupiter.api.Assertions.assertThrows(
                WorkbenchRunCursorExpiredException.class,
                () -> service.subscribe(
                        WorkbenchRunTestFixtures.OWNER,
                        WorkbenchRunTestFixtures.WORKBENCH_ID,
                        "run-1", 7L, sink));

        verify(telemetry).sseReconnect("CURSOR_EXPIRED");
    }

    @Test
    void initialSubscriptionShouldNotCountAsReconnect() {
        ChatRun run = WorkbenchRunTestFixtures.runningRun();
        AuthorizedWorkbenchRun authorized = authorize(run);
        WorkbenchRunStreamSink sink = mock(WorkbenchRunStreamSink.class);
        when(replayService.subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(0L), any()))
                .thenReturn(mock(ChatRunStreamHandle.class));

        service.subscribe(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID,
                "run-1", 0L, sink);

        verifyNoInteractions(telemetry);
    }

    @Test
    void failedReconnectShouldRecordStableFailureResult() {
        ChatRun run = WorkbenchRunTestFixtures.runningRun();
        AuthorizedWorkbenchRun authorized = authorize(run);
        WorkbenchRunStreamSink sink = mock(WorkbenchRunStreamSink.class);
        IllegalStateException failure = new IllegalStateException(
                "event store unavailable");
        when(replayService.subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenThrow(failure);

        assertEquals(failure,
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> service.subscribe(
                                WorkbenchRunTestFixtures.OWNER,
                                WorkbenchRunTestFixtures.WORKBENCH_ID,
                                "run-1", 7L, sink)));

        verify(telemetry).sseReconnect("FAILED");
    }

    private AuthorizedWorkbenchRun authorize(ChatRun run) {
        AuthorizedWorkbenchRun authorized =
                AuthorizedWorkbenchRun.verified(
                        WorkbenchRunTestFixtures.workbench(),
                        WorkbenchRunTestFixtures.snapshot(), run);
        when(accessResolver.requireAuthorized(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1"))
                .thenReturn(authorized);
        return authorized;
    }
}
