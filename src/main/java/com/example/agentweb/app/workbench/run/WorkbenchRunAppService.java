package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.AuthorizedChatRunEventReplayService;
import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.app.chatrun.ChatRunStreamHandle;
import com.example.agentweb.app.chatrun.ChatRunTerminalFinalizer;
import com.example.agentweb.app.chatrun.EventCursorExpiredException;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunCancellationDecision;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * Workbench Run 的提交、Owner-scoped 查询、停止与事件订阅编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class WorkbenchRunAppService {

    private final WorkbenchRunPreparationService preparationService;
    private final WorkbenchRunSubmissionCommitter submissionCommitter;
    private final WorkbenchRunAccessResolver accessResolver;
    private final ChatRunEventAppender eventAppender;
    private final ChatRunTerminalFinalizer terminalFinalizer;
    private final ChatRunRuntimeHandleStore handleStore;
    private final AgentExecutionGateway executionGateway;
    private final AuthorizedChatRunEventReplayService replayService;
    private final SafeWorkbenchRunLogger safeLogger;
    private final WorkbenchTelemetry telemetry;
    private final Clock clock;

    public WorkbenchRunAppService(
            WorkbenchRunPreparationService preparationService,
            WorkbenchRunSubmissionCommitter submissionCommitter,
            WorkbenchRunAccessResolver accessResolver,
            ChatRunEventAppender eventAppender,
            ChatRunTerminalFinalizer terminalFinalizer,
            ChatRunRuntimeHandleStore handleStore,
            AgentExecutionGateway executionGateway,
            AuthorizedChatRunEventReplayService replayService,
            SafeWorkbenchRunLogger safeLogger,
            WorkbenchTelemetry telemetry,
            Clock clock) {
        this.preparationService = Objects.requireNonNull(
                preparationService, "preparationService");
        this.submissionCommitter = Objects.requireNonNull(
                submissionCommitter, "submissionCommitter");
        this.accessResolver = Objects.requireNonNull(
                accessResolver, "accessResolver");
        this.eventAppender = Objects.requireNonNull(
                eventAppender, "eventAppender");
        this.terminalFinalizer = Objects.requireNonNull(
                terminalFinalizer, "terminalFinalizer");
        this.handleStore = Objects.requireNonNull(
                handleStore, "handleStore");
        this.executionGateway = Objects.requireNonNull(
                executionGateway, "executionGateway");
        this.replayService = Objects.requireNonNull(
                replayService, "replayService");
        this.safeLogger = Objects.requireNonNull(
                safeLogger, "safeLogger");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkbenchRunSubmissionResult submit(
            OwnerReference actor, SubmitWorkbenchRunCommand command) {
        PreparedWorkbenchRun prepared = preparationService.prepare(
                actor, command);
        return submissionCommitter.commit(actor, prepared);
    }

    @Transactional(readOnly = true)
    public WorkbenchRunView find(
            OwnerReference actor, WorkbenchId workbenchId,
            String runId) {
        AuthorizedWorkbenchRun authorized = accessResolver
                .requireAuthorized(actor, workbenchId, runId);
        return WorkbenchRunView.from(
                authorized.getRun(), authorized.getSnapshot());
    }

    @Transactional
    public WorkbenchRunStopResult stop(
            OwnerReference actor, WorkbenchId workbenchId,
            String runId) {
        AuthorizedWorkbenchRun authorized = accessResolver
                .requireAuthorized(actor, workbenchId, runId);
        final ChatRun run = authorized.getRun();
        WorkbenchRunSnapshot snapshot = authorized.getSnapshot();
        Instant now = clock.instant();
        ChatRunCancellationDecision decision =
                run.requestCancellation(now);
        if (decision.isTerminalTransition()) {
            terminalFinalizer.finalizeFirstTerminal(run, now);
        } else if (decision.isChanged()) {
            eventAppender.appendToExistingRun(
                    run,
                    Collections.singletonList(new ChatRunEventDraft(
                            "run_status",
                            WorkbenchRunEventPayloadFactory.status(
                                    snapshot, run.getStatus(), now))),
                    now);
            scheduleRuntimeStop(run.getId(), decision);
        }
        return WorkbenchRunStopResult.from(run);
    }

    @Transactional(readOnly = true)
    public WorkbenchRunStreamHandle subscribe(
            OwnerReference actor, WorkbenchId workbenchId,
            String runId, long cursor, WorkbenchRunStreamSink sink) {
        AuthorizedWorkbenchRun authorized = accessResolver
                .requireAuthorized(actor, workbenchId, runId);
        try {
            ChatRunStreamHandle handle = replayService.subscribe(
                    authorized.getRun(), cursor,
                    new WorkbenchRunProjectingStreamSink(
                            authorized.getSnapshot(), sink,
                            telemetry, clock));
            recordReconnect(cursor, "SUCCESS");
            return WorkbenchRunStreamHandle.from(handle);
        } catch (EventCursorExpiredException failure) {
            recordReconnect(cursor, "CURSOR_EXPIRED");
            throw new WorkbenchRunCursorExpiredException(
                    failure.getRunId(), failure.getEarliestRetainedSeq(),
                    failure.getLastEventSeq(), failure.getMessage());
        } catch (RuntimeException failure) {
            recordReconnect(cursor, "FAILED");
            throw failure;
        }
    }

    private void recordReconnect(long cursor, String result) {
        if (cursor > 0L) {
            telemetry.sseReconnect(result);
        }
    }

    private void scheduleRuntimeStop(
            final ChatRunId runId,
            ChatRunCancellationDecision decision) {
        if (!decision.isProcessStopRequired()) {
            return;
        }
        eventAppender.afterCommit(new Runnable() {
            @Override
            public void run() {
                requestPersistedRuntimeStop(runId);
            }
        });
    }

    private void requestPersistedRuntimeStop(ChatRunId runId) {
        try {
            Optional<RuntimeHandle> persisted = handleStore.find(runId);
            if (persisted.isPresent()) {
                executionGateway.requestStop(persisted.get());
            }
        } catch (RuntimeException failure) {
            safeLogger.runtimeStopFailed(
                    runId.getValue(),
                    failure.getClass().getSimpleName());
        }
    }
}
