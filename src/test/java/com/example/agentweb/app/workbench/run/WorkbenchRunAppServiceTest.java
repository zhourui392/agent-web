package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.AuthorizedChatRunEventReplayService;
import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunStreamHandle;
import com.example.agentweb.app.chatrun.ChatRunTerminalFinalizer;
import com.example.agentweb.app.chatrun.EventCursorExpiredException;
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
 * Dynamic Stage Workbench Run 查询、停止与事件订阅编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchRunAppServiceTest {

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
        accessResolver = mock(WorkbenchRunAccessResolver.class);
        eventAppender = mock(ChatRunEventAppender.class);
        terminalFinalizer = mock(ChatRunTerminalFinalizer.class);
        handleStore = mock(ChatRunRuntimeHandleStore.class);
        executionGateway = mock(AgentExecutionGateway.class);
        replayService = mock(AuthorizedChatRunEventReplayService.class);
        safeLogger = mock(SafeWorkbenchRunLogger.class);
        telemetry = mock(WorkbenchTelemetry.class);
        WorkbenchRunCancellationCoordinator cancellationCoordinator =
                new WorkbenchRunCancellationCoordinator(
                        eventAppender, terminalFinalizer, handleStore,
                        executionGateway, safeLogger,
                        Clock.fixed(
                                WorkbenchStageRunTestFixtures.NOW
                                        .plusSeconds(4),
                                ZoneOffset.UTC));
        service = new WorkbenchRunAppService(
                accessResolver, cancellationCoordinator,
                replayService, telemetry,
                Clock.fixed(
                        WorkbenchStageRunTestFixtures.NOW.plusSeconds(4),
                        ZoneOffset.UTC));
    }

    @Test
    void should_ReturnStageIdentityWithoutPhase_When_FindingRun() {
        // Given
        ChatRun run = WorkbenchStageRunTestFixtures.runningRun();
        authorize(run);

        // When
        WorkbenchRunView view = service.find(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);

        // Then
        assertEquals(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                view.getRunId());
        assertEquals(WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                view.getStageInstanceIdentifier());
    }

    @Test
    void should_PersistCancellationBeforeRuntimeStop_When_RunIsRunning() {
        // Given
        ChatRun run = WorkbenchStageRunTestFixtures.runningRun();
        authorize(run);
        RuntimeHandle handle = new RuntimeHandle("execution-1", "handle-1");
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        ArgumentCaptor<Runnable> afterCommit =
                ArgumentCaptor.forClass(Runnable.class);

        // When
        WorkbenchRunStopResult result = service.stop(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);

        // Then
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
    }

    @Test
    void should_KeepCancellationIntent_When_RuntimeStopFails() {
        ChatRun run = WorkbenchStageRunTestFixtures.runningRun();
        authorize(run);
        RuntimeHandle handle = new RuntimeHandle("execution-1", "handle-1");
        when(handleStore.find(run.getId())).thenReturn(Optional.of(handle));
        org.mockito.Mockito.doThrow(new IllegalStateException("runtime down"))
                .when(executionGateway).requestStop(handle);
        ArgumentCaptor<Runnable> afterCommit =
                ArgumentCaptor.forClass(Runnable.class);

        WorkbenchRunStopResult result = service.stop(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);
        verify(eventAppender).afterCommit(afterCommit.capture());

        assertDoesNotThrow(() -> afterCommit.getValue().run());
        assertEquals(ChatRunStatus.CANCEL_REQUESTED, result.getStatus());
        verify(terminalFinalizer, never()).finalizeFirstTerminal(any(), any());
        verify(safeLogger).runtimeStopFailed(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                "IllegalStateException");
    }

    @Test
    void should_FinalizeWithoutExternalStop_When_RunIsPending() {
        ChatRun run = WorkbenchStageRunTestFixtures.pendingRun();
        authorize(run);

        WorkbenchRunStopResult result = service.stop(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);

        assertEquals(ChatRunStatus.CANCELLED, result.getStatus());
        verify(terminalFinalizer).finalizeFirstTerminal(
                run, WorkbenchStageRunTestFixtures.NOW.plusSeconds(4));
        verify(eventAppender, never()).afterCommit(any());
        verifyNoInteractions(handleStore, executionGateway);
    }

    @Test
    void should_AuthorizeBeforeReplay_When_Subscribing() {
        ChatRun run = WorkbenchStageRunTestFixtures.runningRun();
        AuthorizedWorkbenchRun authorized = authorize(run);
        WorkbenchRunStreamSink sink = mock(WorkbenchRunStreamSink.class);
        ChatRunStreamHandle commonHandle = mock(ChatRunStreamHandle.class);
        when(replayService.subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(commonHandle);

        service.subscribe(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                7L, sink);

        InOrder order = inOrder(accessResolver, replayService);
        order.verify(accessResolver).requireAuthorized(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);
        order.verify(replayService).subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(7L), any());
        verify(telemetry).sseReconnect("SUCCESS");
    }

    @Test
    void should_RecordExpiredCursor_When_ReplayWindowIsGone() {
        ChatRun run = WorkbenchStageRunTestFixtures.runningRun();
        AuthorizedWorkbenchRun authorized = authorize(run);
        WorkbenchRunStreamSink sink = mock(WorkbenchRunStreamSink.class);
        when(replayService.subscribe(
                org.mockito.ArgumentMatchers.eq(authorized.getRun()),
                org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenThrow(new EventCursorExpiredException(
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                        8L, 20L));

        org.junit.jupiter.api.Assertions.assertThrows(
                WorkbenchRunCursorExpiredException.class,
                () -> service.subscribe(
                        WorkbenchStageRunTestFixtures.OWNER,
                        WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                        7L, sink));

        verify(telemetry).sseReconnect("CURSOR_EXPIRED");
    }

    private AuthorizedWorkbenchRun authorize(ChatRun run) {
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        AuthorizedWorkbenchRun authorized =
                AuthorizedWorkbenchRun.verified(
                        fixture.workbench(), fixture.snapshot(), run);
        when(accessResolver.requireAuthorized(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(authorized);
        return authorized;
    }
}
