package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import com.example.agentweb.app.chatrun.RunCapacityExceededException;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseConversationProvisioning;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * 已准备 Workbench Run 的原子持久化编排；Runtime 只在提交后启动。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class WorkbenchRunSubmissionCommitter {

    private final WorkbenchRepository workbenchRepository;
    private final WorkspaceSnapshotRepository workspaceSnapshotRepository;
    private final WorkbenchRunSnapshotRepository snapshotRepository;
    private final WorkbenchRunPromptPayloadRepository promptRepository;
    private final HandoffReceptionRepository receptionRepository;
    private final SessionRepository sessionRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunEventAppender eventAppender;
    private final ChatRunLauncher launcher;
    private final ChatRunActivityGuard activityGuard;
    private final ChatRunQueryService runQueryService;
    private final ChatRunStreamSettings streamSettings;
    private final WorkbenchRunSubmissionExecutor submissionExecutor;
    private final Clock clock;

    public WorkbenchRunSubmissionCommitter(
            WorkbenchRepository workbenchRepository,
            WorkspaceSnapshotRepository workspaceSnapshotRepository,
            WorkbenchRunSnapshotRepository snapshotRepository,
            WorkbenchRunPromptPayloadRepository promptRepository,
            HandoffReceptionRepository receptionRepository,
            SessionRepository sessionRepository,
            ChatRunRepository runRepository,
            ChatRunEventAppender eventAppender,
            ChatRunLauncher launcher,
            ChatRunActivityGuard activityGuard,
            ChatRunQueryService runQueryService,
            ChatRunStreamSettings streamSettings,
            WorkbenchRunSubmissionExecutor submissionExecutor,
            Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.workspaceSnapshotRepository = Objects.requireNonNull(
                workspaceSnapshotRepository, "workspaceSnapshotRepository");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.promptRepository = Objects.requireNonNull(
                promptRepository, "promptRepository");
        this.receptionRepository = Objects.requireNonNull(
                receptionRepository, "receptionRepository");
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.runRepository = Objects.requireNonNull(
                runRepository, "runRepository");
        this.eventAppender = Objects.requireNonNull(
                eventAppender, "eventAppender");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.activityGuard = Objects.requireNonNull(
                activityGuard, "activityGuard");
        this.runQueryService = Objects.requireNonNull(
                runQueryService, "runQueryService");
        this.streamSettings = Objects.requireNonNull(
                streamSettings, "streamSettings");
        this.submissionExecutor = Objects.requireNonNull(
                submissionExecutor, "submissionExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkbenchRunSubmissionResult commit(
            OwnerReference actor, PreparedWorkbenchRun prepared) {
        if (actor == null || prepared == null) {
            throw new IllegalArgumentException(
                    "workbench run actor and preparation are required");
        }
        return submissionExecutor.execute(
                () -> commitInTransaction(actor, prepared));
    }

    @Transactional(readOnly = true)
    public Optional<WorkbenchRunSubmissionResult> replayIfPresent(
            OwnerReference actor, SubmitWorkbenchRunCommand command) {
        if (actor == null || command == null) {
            throw new IllegalArgumentException(
                    "workbench run actor and command are required");
        }
        Optional<WorkbenchRunSnapshot> existing = snapshotRepository
                .findReplayCandidate(
                        actor, command.getWorkbenchId(), command.getPhase(),
                        command.getIdempotencyKey());
        if (!existing.isPresent()) {
            return Optional.empty();
        }
        Workbench workbench = requireOwnedWorkbench(
                actor, command.getWorkbenchId());
        return Optional.of(replay(
                workbench, actor, command, existing.get()));
    }

    private WorkbenchRunSubmissionResult commitInTransaction(
            OwnerReference actor, PreparedWorkbenchRun prepared) {
        SubmitWorkbenchRunCommand command = prepared.getCommand();
        WorkbenchRunSnapshot candidate = prepared.getSnapshot();
        Workbench workbench = requireOwnedWorkbench(
                actor, command.getWorkbenchId());
        Optional<WorkbenchRunSnapshot> existing = snapshotRepository
                .findByWorkbenchPhaseAndIdempotencyKey(
                        command.getWorkbenchId(), command.getPhase(),
                        command.getIdempotencyKey());
        if (existing.isPresent()) {
            return replay(workbench, actor, command, existing.get());
        }
        requireCapacity();
        PhaseConversationProvisioning provisioning = obscureOwner(() ->
                workbench.planConversationEnsure(
                        command.getPhase(), actor,
                        command.getExpectedVersion()));
        String sessionId = provisioning.requireCurrentConversationId();
        ChatSession session = requireTrustedSession(
                provisioning, sessionId);
        activityGuard.requireInactive(sessionId);
        Instant now = clock.instant();
        obscureOwner(() -> workbench.prepareRun(
                command.getPhase(), candidate.getRunId(),
                command.getRunMode(), prepared.getReviewConfirmation(),
                actor, command.getExpectedVersion(), now));
        long userMessageId = sessionRepository.addMessageReturningId(
                sessionId, new ChatMessage("user", command.getMessage(), now));
        final ChatRun run = ChatRun.submit(
                ChatRunId.of(candidate.getRunId()), sessionId,
                userMessageId, command.getIdempotencyKey(), false,
                RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        provisioning.getContextId(), candidate.getRunId()),
                now);
        eventAppender.appendToNewRun(
                run, Collections.singletonList(
                        new ChatRunEventDraft(
                                "run_status",
                                WorkbenchRunEventPayloadFactory.status(
                                        candidate, run.getStatus(), now))),
                now);
        workspaceSnapshotRepository.add(prepared.getWorkspaceSnapshot());
        snapshotRepository.add(candidate);
        promptRepository.add(prepared.getPromptPayload());
        if (prepared.getHandoffReception() != null) {
            receptionRepository.save(prepared.getHandoffReception());
        }
        workbenchRepository.update(workbench);
        eventAppender.afterCommit(new Runnable() {
            @Override
            public void run() {
                launcher.launch(run.getId());
            }
        });
        return WorkbenchRunSubmissionResult.from(
                run, candidate, workbench, false);
    }

    private WorkbenchRunSubmissionResult replay(
            Workbench workbench, OwnerReference actor,
            SubmitWorkbenchRunCommand command,
            WorkbenchRunSnapshot existing) {
        String runId = existing.requireReplay(
                workbench, actor, command.getPhase(),
                command.getIdempotencyKey(), command.getRequestHash());
        WorkbenchRunPromptPayload prompt = promptRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "workbench run prompt payload is unavailable"));
        existing.requirePromptPayload(prompt);
        ChatRun run = runRepository.findById(ChatRunId.of(runId))
                .orElseThrow(() -> new IllegalStateException(
                        "workbench chat run is unavailable"));
        run.requireWorkbenchExecutionContext(
                originReference(command));
        return WorkbenchRunSubmissionResult.from(
                run, existing, workbench, true);
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor,
            com.example.agentweb.domain.workbench.WorkbenchId workbenchId) {
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        obscureOwner(() -> {
            workbench.requireOwnedBy(actor);
            return workbench;
        });
        return workbench;
    }

    private ChatSession requireTrustedSession(
            PhaseConversationProvisioning provisioning,
            String sessionId) {
        ChatSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            throw new IllegalStateException(
                    "phase conversation session is unavailable");
        }
        session.requireActiveWorkbenchPhase(
                sessionId, provisioning.getAgentType(),
                provisioning.getPrimaryRepositoryRoot(),
                provisioning.getEnvironment(), provisioning.getContextId(),
                provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName(),
                provisioning.getCurrentConversationCreatedAt());
        return session;
    }

    private void requireCapacity() {
        int capacity = Math.max(1, streamSettings.getMaxActiveRuns());
        if (runQueryService.countActiveRuns() >= capacity) {
            throw new RunCapacityExceededException(capacity);
        }
    }

    private String originReference(SubmitWorkbenchRunCommand command) {
        return command.getWorkbenchId().getValue()
                + ":" + command.getPhase().name();
    }

    private <T> T obscureOwner(DomainAction<T> action) {
        try {
            return action.execute();
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw failure;
        }
    }

    @FunctionalInterface
    private interface DomainAction<T> {
        T execute();
    }
}
