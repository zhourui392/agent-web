package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import com.example.agentweb.app.chatrun.RunCapacityExceededException;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeSelectionStore;
import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.app.runtime.port.RuntimeProfileSelector;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
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
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * 已准备 Dynamic Stage Run 的原子持久化编排。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
public class WorkbenchStageRunSubmissionCommitter {

    private final WorkbenchRepository workbenchRepository;
    private final WorkspaceSnapshotRepository workspaceSnapshotRepository;
    private final WorkbenchStageRunSnapshotRepository snapshotRepository;
    private final WorkbenchStageRunPromptPayloadRepository promptRepository;
    private final WorkbenchStageUploadedConversationAttachmentRepository
            attachmentRepository;
    private final UploadedAttachmentPolicy attachmentPolicy;
    private final SessionRepository sessionRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunEventAppender eventAppender;
    private final ChatRunLauncher launcher;
    private final ChatRunActivityGuard activityGuard;
    private final ChatRunQueryService runQueryService;
    private final ChatRunStreamSettings streamSettings;
    private final WorkbenchStageRunSubmissionExecutor submissionExecutor;
    private final Clock clock;
    private final RuntimeProfileSelector profileSelector;
    private final ChatRunRuntimeSelectionStore selectionStore;

    public WorkbenchStageRunSubmissionCommitter(
            WorkbenchRepository workbenchRepository,
            WorkspaceSnapshotRepository workspaceSnapshotRepository,
            WorkbenchStageRunSnapshotRepository snapshotRepository,
            WorkbenchStageRunPromptPayloadRepository promptRepository,
            WorkbenchStageUploadedConversationAttachmentRepository
                    attachmentRepository,
            UploadedAttachmentPolicy attachmentPolicy,
            SessionRepository sessionRepository,
            ChatRunRepository runRepository,
            ChatRunEventAppender eventAppender,
            ChatRunLauncher launcher,
            ChatRunActivityGuard activityGuard,
            ChatRunQueryService runQueryService,
            ChatRunStreamSettings streamSettings,
            WorkbenchStageRunSubmissionExecutor submissionExecutor,
            Clock clock) {
        this(workbenchRepository, workspaceSnapshotRepository, snapshotRepository,
                promptRepository, attachmentRepository, attachmentPolicy,
                sessionRepository, runRepository, eventAppender, launcher,
                activityGuard, runQueryService, streamSettings, submissionExecutor,
                clock, null, null);
    }

    @Autowired
    public WorkbenchStageRunSubmissionCommitter(
            WorkbenchRepository workbenchRepository,
            WorkspaceSnapshotRepository workspaceSnapshotRepository,
            WorkbenchStageRunSnapshotRepository snapshotRepository,
            WorkbenchStageRunPromptPayloadRepository promptRepository,
            WorkbenchStageUploadedConversationAttachmentRepository
                    attachmentRepository,
            UploadedAttachmentPolicy attachmentPolicy,
            SessionRepository sessionRepository,
            ChatRunRepository runRepository,
            ChatRunEventAppender eventAppender,
            ChatRunLauncher launcher,
            ChatRunActivityGuard activityGuard,
            ChatRunQueryService runQueryService,
            ChatRunStreamSettings streamSettings,
            WorkbenchStageRunSubmissionExecutor submissionExecutor,
            Clock clock,
            RuntimeProfileSelector profileSelector,
            ChatRunRuntimeSelectionStore selectionStore) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.workspaceSnapshotRepository = Objects.requireNonNull(
                workspaceSnapshotRepository, "workspaceSnapshotRepository");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.promptRepository = Objects.requireNonNull(
                promptRepository, "promptRepository");
        this.attachmentRepository = Objects.requireNonNull(
                attachmentRepository, "attachmentRepository");
        this.attachmentPolicy = Objects.requireNonNull(
                attachmentPolicy, "attachmentPolicy");
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
        this.profileSelector = profileSelector;
        this.selectionStore = selectionStore;
    }

    public WorkbenchStageRunSubmissionResult commit(
            OwnerReference actor, PreparedWorkbenchStageRun prepared) {
        if (actor == null || prepared == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run actor and preparation are required");
        }
        return submissionExecutor.execute(
                () -> commitInTransaction(actor, prepared));
    }

    @Transactional(readOnly = true)
    public Optional<WorkbenchStageRunSubmissionResult> replayIfPresent(
            OwnerReference actor, SubmitWorkbenchStageRunCommand command) {
        if (actor == null || command == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run actor and command are required");
        }
        Optional<WorkbenchStageRunSnapshot> existing = snapshotRepository
                .findReplayCandidate(
                        actor, command.getWorkbenchId(),
                        command.getStageInstanceIdentifier(),
                        command.getIdempotencyKey());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Workbench workbench = requireOwnedWorkbench(
                actor, command.getWorkbenchId());
        return Optional.of(replay(
                workbench, command, existing.get()));
    }

    private WorkbenchStageRunSubmissionResult commitInTransaction(
            OwnerReference actor, PreparedWorkbenchStageRun prepared) {
        SubmitWorkbenchStageRunCommand command = prepared.getCommand();
        WorkbenchStageRunSnapshot candidate = prepared.getSnapshot();
        Workbench workbench = requireOwnedWorkbench(
                actor, command.getWorkbenchId());
        Optional<WorkbenchStageRunSnapshot> existing = snapshotRepository
                .findByWorkbenchStageAndIdempotencyKey(
                        command.getWorkbenchId(),
                        command.getStageInstanceIdentifier(),
                        command.getIdempotencyKey());
        if (existing.isPresent()) {
            return replay(workbench, command, existing.get());
        }
        WorkbenchStageConversationProvisioning provisioning = obscureOwner(
                () -> workbench.planStageConversationEnsure(
                        command.getStageInstanceIdentifier(), actor,
                        command.getExpectedVersion()));
        String sessionId = provisioning.requireCurrentConversationId();
        ChatSession session = requireTrustedSession(provisioning, sessionId);
        activityGuard.requireInactive(sessionId);
        requireCapacity();
        Instant now = clock.instant();
        obscureOwner(() -> workbench.prepareStageRun(
                command.getStageInstanceIdentifier(), candidate.getRunId(),
                command.getRunMode(), actor,
                command.getExpectedVersion(), now));
        long userMessageIdentifier =
                sessionRepository.addMessageReturningId(
                        sessionId,
                        new ChatMessage(
                                "user", command.getMessage(), now));
        ChatRun run = ChatRun.submit(
                ChatRunId.of(candidate.getRunId()), sessionId,
                userMessageIdentifier, command.getIdempotencyKey(), false,
                RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        provisioning.getContextId(), candidate.getRunId()),
                now);
        bindUploadedAttachments(
                prepared, provisioning, candidate.getRunId(), now);
        eventAppender.appendToNewRun(
                run, Collections.singletonList(
                        new ChatRunEventDraft(
                                "run_status",
                                WorkbenchRunEventPayloadFactory.status(
                                        candidate, run.getStatus(), now))),
                now);
        persistRuntimeSelection(command, session, candidate, run.getId());
        workspaceSnapshotRepository.add(prepared.getWorkspaceSnapshot());
        snapshotRepository.add(candidate);
        promptRepository.add(prepared.getPromptPayload());
        workbenchRepository.update(workbench);
        eventAppender.afterCommit(new Runnable() {
            @Override
            public void run() {
                launcher.launch(run.getId());
            }
        });
        return WorkbenchStageRunSubmissionResult.from(
                run, candidate, workbench, false);
    }

    private void persistRuntimeSelection(SubmitWorkbenchStageRunCommand command,
                                          ChatSession session,
                                          WorkbenchStageRunSnapshot candidate,
                                          ChatRunId runId) {
        if (profileSelector == null || selectionStore == null
                || !profileSelector.hasProfiles()) {
            return;
        }
        RuntimeSelection selected = profileSelector.selection(
                session.getAgentType(), AgentRuntimeSurface.WORKBENCH,
                command.getRunMode(), command.getProfileId(), command.getModel(),
                command.getReasoningEffort());
        RuntimeSelection frozen = new RuntimeSelection(
                selected.getProfileId(), selected.getAgentType(), selected.getEndpoint(),
                selected.getModel(), selected.getReasoningEffort(),
                selected.getRuntimeEnvironment(),
                RuntimeVersionPolicy.exact(candidate.getRuntimeEnforcement().getRuntimeVersion()));
        selectionStore.save(runId, frozen);
    }

    private void bindUploadedAttachments(
            PreparedWorkbenchStageRun prepared,
            WorkbenchStageConversationProvisioning provisioning,
            String runIdentifier, Instant now) {
        WorkbenchStageUploadedAttachmentBinding currentBinding =
                new WorkbenchStageUploadedAttachmentBinding(
                        provisioning.getOwner(),
                        provisioning.getWorkbenchId(),
                        provisioning.getStageInstanceIdentifier(),
                        provisioning.requireCurrentConversationId(),
                        provisioning.getCurrentConversationGeneration());
        for (VerifiedWorkbenchStageUploadedConversationAttachment verified
                : prepared.getVerifiedAttachments()
                .getUploadedAttachments()) {
            verified.requireBinding(currentBinding);
            WorkbenchStageUploadedConversationAttachment attachment =
                    attachmentRepository.findById(verified.getAttachmentId())
                            .orElseThrow(
                                    WorkbenchDomainException
                                            ::runBindingCorrupted);
            long expectedVersion = attachment.getVersion();
            attachment.bindToRun(
                    verified, runIdentifier, now, attachmentPolicy);
            attachmentRepository.update(attachment, expectedVersion);
        }
    }

    private WorkbenchStageRunSubmissionResult replay(
            Workbench workbench, SubmitWorkbenchStageRunCommand command,
            WorkbenchStageRunSnapshot existing) {
        String runIdentifier = existing.requireReplay(
                command.getWorkbenchId(),
                command.getStageInstanceIdentifier(),
                command.getIdempotencyKey(), command.getRequestHash());
        WorkbenchRunPromptPayload prompt =
                promptRepository.findByRunId(runIdentifier)
                        .orElseThrow(() -> new IllegalStateException(
                                "Workbench Stage Run prompt payload is unavailable"));
        existing.requirePromptPayload(prompt);
        ChatRun run = runRepository.findById(ChatRunId.of(runIdentifier))
                .orElseThrow(() -> new IllegalStateException(
                        "Workbench Stage ChatRun is unavailable"));
        existing.requireExactRun(workbench, run, runIdentifier);
        return WorkbenchStageRunSubmissionResult.from(
                run, existing, workbench, true);
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        obscureOwner(() -> {
            workbench.requireOwnedBy(actor);
            return workbench;
        });
        return workbench;
    }

    private ChatSession requireTrustedSession(
            WorkbenchStageConversationProvisioning provisioning,
            String sessionId) {
        ChatSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            throw new IllegalStateException(
                    "Dynamic Stage Conversation Session is unavailable");
        }
        session.requireActiveWorkbenchStage(
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
