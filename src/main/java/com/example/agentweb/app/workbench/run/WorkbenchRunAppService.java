package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.AuthorizedChatRunEventReplayService;
import com.example.agentweb.app.chatrun.ChatRunStreamHandle;
import com.example.agentweb.app.chatrun.EventCursorExpiredException;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
    private final WorkbenchRunCancellationCoordinator cancellationCoordinator;
    private final AuthorizedChatRunEventReplayService replayService;
    private final WorkbenchTelemetry telemetry;
    private final Clock clock;

    public WorkbenchRunAppService(
            WorkbenchRunPreparationService preparationService,
            WorkbenchRunSubmissionCommitter submissionCommitter,
            WorkbenchRunAccessResolver accessResolver,
            WorkbenchRunCancellationCoordinator cancellationCoordinator,
            AuthorizedChatRunEventReplayService replayService,
            WorkbenchTelemetry telemetry, Clock clock) {
        this.preparationService = Objects.requireNonNull(
                preparationService, "preparationService");
        this.submissionCommitter = Objects.requireNonNull(
                submissionCommitter, "submissionCommitter");
        this.accessResolver = Objects.requireNonNull(
                accessResolver, "accessResolver");
        this.cancellationCoordinator = Objects.requireNonNull(
                cancellationCoordinator, "cancellationCoordinator");
        this.replayService = Objects.requireNonNull(
                replayService, "replayService");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkbenchRunSubmissionResult submit(
            OwnerReference actor, SubmitWorkbenchRunCommand command) {
        Optional<WorkbenchRunSubmissionResult> replayed =
                submissionCommitter.replayIfPresent(actor, command);
        if (replayed.isPresent()) {
            return replayed.get();
        }
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
        return cancellationCoordinator.cancel(
                authorized.getSnapshot(), authorized.getRun())
                .getStopResult();
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

}
