package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunIdGenerator;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import com.example.agentweb.app.runtime.port.RuntimePreflightGateway;
import com.example.agentweb.app.runtime.port.RuntimePreflightReport;
import com.example.agentweb.app.runtime.port.RuntimePreflightRequest;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.WorkspaceSnapshotIdGenerator;
import com.example.agentweb.app.workbench.document.DocumentContentView;
import com.example.agentweb.app.workbench.document.port.ScopedDocumentGateway;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.app.workbench.port.WorkspaceDevelopmentContextGateway;
import com.example.agentweb.app.workbench.port.WorkspaceSnapshotGateway;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PreparedWorkbenchStagePrompt;
import com.example.agentweb.domain.workbench.ResolvedCapabilityResolution;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageRunAttachmentSet;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchStageConversationHistory;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPreparationPlan;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPromptComposer;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
import com.example.agentweb.domain.workbench.context.WorkbenchContextManifest;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCapabilityResolver;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 外部 Runtime 启动前准备 Dynamic Stage Run 的完整不可变候选。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
public class WorkbenchStageRunPreparationService {

    private static final SnapshotPurpose RUN_START_PURPOSE =
            SnapshotPurpose.of("WORKBENCH_RUN_START");

    private final WorkbenchRunAvailability availability;
    private final WorkbenchRepository workbenchRepository;
    private final SessionRepository sessionRepository;
    private final WorkbenchStageHistoryQuery historyQuery;
    private final WorkbenchContextManifestQuery contextManifestQuery;
    private final WorkbenchStageCapabilityResolver capabilityResolver;
    private final WorkspaceSnapshotIdGenerator snapshotIdGenerator;
    private final WorkspaceSnapshotGateway snapshotGateway;
    private final WorkspaceDevelopmentContextGateway developmentContextGateway;
    private final ScopedDocumentGateway documentGateway;
    private final WorkbenchStageUploadedConversationAttachmentRepository
            stageAttachmentRepository;
    private final RuntimePreflightGateway preflightGateway;
    private final ChatRunIdGenerator runIdGenerator;
    private final WorkbenchTelemetry telemetry;
    private final WorkbenchRunPreparationSettings settings;
    private final Clock clock;

    public WorkbenchStageRunPreparationService(
            WorkbenchRunAvailability availability,
            WorkbenchRepository workbenchRepository,
            SessionRepository sessionRepository,
            WorkbenchStageHistoryQuery historyQuery,
            WorkbenchContextManifestQuery contextManifestQuery,
            WorkbenchStageCapabilityResolver capabilityResolver,
            WorkspaceSnapshotIdGenerator snapshotIdGenerator,
            WorkspaceSnapshotGateway snapshotGateway,
            WorkspaceDevelopmentContextGateway developmentContextGateway,
            ScopedDocumentGateway documentGateway,
            WorkbenchStageUploadedConversationAttachmentRepository
                    stageAttachmentRepository,
            RuntimePreflightGateway preflightGateway,
            ChatRunIdGenerator runIdGenerator,
            WorkbenchTelemetry telemetry,
            WorkbenchRunPreparationSettings settings,
            Clock clock) {
        this.availability = Objects.requireNonNull(
                availability, "availability");
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.historyQuery = Objects.requireNonNull(
                historyQuery, "historyQuery");
        this.contextManifestQuery = Objects.requireNonNull(
                contextManifestQuery, "contextManifestQuery");
        this.capabilityResolver = Objects.requireNonNull(
                capabilityResolver, "capabilityResolver");
        this.snapshotIdGenerator = Objects.requireNonNull(
                snapshotIdGenerator, "snapshotIdGenerator");
        this.snapshotGateway = Objects.requireNonNull(
                snapshotGateway, "snapshotGateway");
        this.developmentContextGateway = Objects.requireNonNull(
                developmentContextGateway, "developmentContextGateway");
        this.documentGateway = Objects.requireNonNull(
                documentGateway, "documentGateway");
        this.stageAttachmentRepository = Objects.requireNonNull(
                stageAttachmentRepository, "stageAttachmentRepository");
        this.preflightGateway = Objects.requireNonNull(
                preflightGateway, "preflightGateway");
        this.runIdGenerator = Objects.requireNonNull(
                runIdGenerator, "runIdGenerator");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PreparedWorkbenchStageRun prepare(
            OwnerReference actor, SubmitWorkbenchStageRunCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        availability.requireAvailable(command.getRunMode());
        Instant preparedAt = clock.instant();
        Workbench workbench = requireOwnedWorkbench(actor, command);
        WorkbenchStageRunPreparationPlan plan = obscureOwner(() ->
                workbench.planStageRunPreparation(
                        command.getStageInstanceIdentifier(),
                        command.getRunMode(), actor,
                        command.getExpectedVersion()));
        WorkbenchStageConversationHistory history =
                loadTrustedHistory(plan);
        WorkbenchContextManifest contextManifest =
                Objects.requireNonNull(
                        contextManifestQuery.load(plan),
                        "Workbench Context Manifest");
        contextManifest.requireCurrent(plan);
        WorkspaceDevelopmentContext developmentContext =
                Objects.requireNonNull(
                        developmentContextGateway.inspect(
                                plan.getRepositoryScope()),
                        "Workspace Development Context");
        plan.requireDevelopmentContext(developmentContext);
        CapabilityPreparation capability = resolveCapabilities(
                plan, command);
        VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments =
                verifyAttachments(
                        plan, command.getAttachments(), preparedAt);
        WorkspaceSnapshot workspaceSnapshot = captureWorkspace(plan);
        RuntimeEnforcementSnapshot runtimeEnforcement = preflight(
                plan, capability.resolution.getBinding());
        String userInput = capability.commandBinding == null
                ? command.getMessage()
                : capability.commandBinding.getExpandedPrompt();
        PreparedWorkbenchStagePrompt prompt =
                WorkbenchStageRunPromptComposer.compose(
                        plan, capability.resolution,
                        capability.commandBinding, contextManifest,
                        developmentContext, workspaceSnapshot, history,
                        verifiedAttachments, userInput);
        return freezePreparedRun(
                command, plan, contextManifest, capability,
                workspaceSnapshot, runtimeEnforcement,
                verifiedAttachments, prompt, preparedAt);
    }

    private WorkbenchStageConversationHistory loadTrustedHistory(
            WorkbenchStageRunPreparationPlan plan) {
        WorkbenchStageConversationProvisioning conversation =
                plan.getConversation();
        requireTrustedSession(conversation);
        WorkbenchStageConversationHistory history = Objects.requireNonNull(
                historyQuery.load(conversation),
                "Workbench Stage Conversation history");
        history.requireCurrent(conversation);
        return history;
    }

    private CapabilityPreparation resolveCapabilities(
            WorkbenchStageRunPreparationPlan plan,
            SubmitWorkbenchStageRunCommand command) {
        try {
            ResolvedCapabilityResolution resolution =
                    capabilityResolver.resolve(
                            plan.getStageSnapshot(), plan.getRunMode(),
                            plan.getAgentType(),
                            settings.getRuntimeCompatibility());
            ResolvedCommandBinding commandBinding =
                    capabilityResolver.resolveCommand(
                            plan.getStageSnapshot(),
                            command.getCommandInvocation());
            telemetry.capabilityResolution("SUCCESS");
            return new CapabilityPreparation(
                    resolution, commandBinding);
        } catch (RuntimeException failure) {
            telemetry.capabilityResolution("FAILED");
            throw failure;
        }
    }

    private VerifiedWorkbenchStageRunAttachmentSet verifyAttachments(
            WorkbenchStageRunPreparationPlan plan,
            List<WorkbenchRunAttachmentReference> attachments,
            Instant verifiedAt) {
        StageAttachmentVerifier verifier = new StageAttachmentVerifier(
                plan, verifiedAt);
        for (WorkbenchRunAttachmentReference attachment : attachments) {
            attachment.resolve(verifier);
        }
        return verifier.result();
    }

    private WorkspaceSnapshot captureWorkspace(
            WorkbenchStageRunPreparationPlan plan) {
        String snapshotId = snapshotIdGenerator.nextId();
        WorkspaceSnapshot snapshot = snapshotGateway.capture(
                snapshotId, plan.getRepositoryScope(), RUN_START_PURPOSE);
        if (snapshot == null
                || !snapshotId.equals(snapshot.getSnapshotId())
                || !RUN_START_PURPOSE.equals(snapshot.getPurpose())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return snapshot;
    }

    private RuntimeEnforcementSnapshot preflight(
            WorkbenchStageRunPreparationPlan plan,
            ResolvedCapabilityBinding capabilityBinding) {
        SandboxMode sandboxMode = SandboxMode.valueOf(
                plan.getWorkspaceAccess().name());
        WorkspaceLayout layout = new WorkspaceLayout(
                plan.getRepositoryScope().primaryRepository()
                        .getRepositoryRoot(),
                plan.getReadableRepositoryRoots(),
                plan.getWritableRepositoryRoots(), sandboxMode);
        RuntimePreflightReport report = preflightGateway.inspect(
                new RuntimePreflightRequest(
                        new RuntimeSelection(
                                plan.getAgentType(),
                                RuntimeVersionPolicy.configured()),
                        layout, capabilityBinding));
        requireExactPreflight(
                plan, capabilityBinding, sandboxMode, report);
        RuntimeLimits limits = settings.getRuntimeLimits();
        return RuntimeEnforcementSnapshot.forRun(
                report.getAgentType().name(), report.getRuntimeVersion(),
                plan.getRepositoryScope().getScopeHash(),
                plan.getRepositoryScope().getPrimaryRepositoryKey(),
                plan.getRunMode(), plan.getWritableRepositoryKeys(),
                limits.getTimeout().getSeconds(),
                limits.getMaxOutputBytes());
    }

    private PreparedWorkbenchStageRun freezePreparedRun(
            SubmitWorkbenchStageRunCommand command,
            WorkbenchStageRunPreparationPlan plan,
            WorkbenchContextManifest contextManifest,
            CapabilityPreparation capability,
            WorkspaceSnapshot workspaceSnapshot,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments,
            PreparedWorkbenchStagePrompt prompt,
            Instant preparedAt) {
        ChatRunId runId = runIdGenerator.nextId();
        WorkbenchRunPromptPayload promptPayload = prompt.freezePayload(
                runId.getValue(), preparedAt);
        WorkbenchStageRunSnapshot snapshot =
                WorkbenchStageRunSnapshot.create(
                        runId.getValue(), command.getWorkbenchId(),
                        command.getStageInstanceIdentifier(),
                        plan.getStageSnapshot(),
                        command.getIdempotencyKey(),
                        command.getRequestHash(), command.getRunMode(),
                        plan.getRepositoryScope(),
                        workspaceSnapshot.reference(),
                        capability.resolution.getBinding(),
                        capability.commandBinding,
                        contextManifest.getContextVersion(),
                        contextManifest.getContextHash(),
                        contextManifest.getDocuments(), prompt.snapshots(),
                        prompt.getPromptHash(), runtimeEnforcement,
                        verifiedAttachments.getRepositoryDocuments(),
                        verifiedAttachments.getUploadedAttachments(),
                        preparedAt);
        return PreparedWorkbenchStageRun.of(
                command, snapshot, workspaceSnapshot,
                promptPayload, verifiedAttachments);
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor,
            SubmitWorkbenchStageRunCommand command) {
        Workbench workbench = workbenchRepository.findById(
                        command.getWorkbenchId())
                .orElseThrow(WorkbenchNotFoundException::new);
        return obscureOwner(() -> {
            workbench.requireOwnedBy(actor);
            return workbench;
        });
    }

    private void requireTrustedSession(
            WorkbenchStageConversationProvisioning conversation) {
        String sessionId = conversation.requireCurrentConversationId();
        ChatSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            throw new IllegalStateException(
                    "Dynamic Stage Conversation Session is unavailable");
        }
        session.requireActiveWorkbenchStage(
                sessionId, conversation.getAgentType(),
                conversation.getPrimaryRepositoryRoot(),
                conversation.getEnvironment(), conversation.getContextId(),
                conversation.getOwner().getOwnerId(),
                conversation.getOwner().getOwnerName(),
                conversation.getCurrentConversationCreatedAt());
    }

    private void requireExactPreflight(
            WorkbenchStageRunPreparationPlan plan,
            ResolvedCapabilityBinding capabilityBinding,
            SandboxMode sandboxMode,
            RuntimePreflightReport report) {
        if (report == null
                || report.getAgentType() != plan.getAgentType()
                || report.getSandboxMode() != sandboxMode
                || report.getReadableRootCount()
                != plan.getReadableRepositoryRoots().size()
                || report.getWritableRootCount()
                != plan.getWritableRepositoryRoots().size()
                || !capabilityBinding.getBindingHash().equals(
                report.getCapabilityBindingHash())) {
            throw new RuntimePreflightException(
                    RuntimePreflightErrorCode.RUNTIME_COMPATIBILITY_MISMATCH,
                    "Runtime preflight facts do not match Dynamic Stage Run");
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

    private final class StageAttachmentVerifier
            implements WorkbenchRunAttachmentReference.Resolver<Void> {

        private final RepositoryScope repositoryScope;
        private final WorkbenchStageRunPreparationPlan plan;
        private final Instant verifiedAt;
        private final List<VerifiedWorkbenchRunAttachment> documents =
                new ArrayList<VerifiedWorkbenchRunAttachment>();
        private final List<
                VerifiedWorkbenchStageUploadedConversationAttachment>
                uploadedAttachments = new ArrayList<
                VerifiedWorkbenchStageUploadedConversationAttachment>();

        private StageAttachmentVerifier(
                WorkbenchStageRunPreparationPlan plan,
                Instant verifiedAt) {
            this.plan = plan;
            this.repositoryScope = plan.getRepositoryScope();
            this.verifiedAt = verifiedAt;
        }

        @Override
        public Void repositoryDocument(
                DocumentReference reference, String contentHash) {
            DocumentContentView observed = Objects.requireNonNull(
                    documentGateway.readContent(repositoryScope, reference),
                    "Observed Dynamic Stage attachment");
            documents.add(VerifiedWorkbenchRunAttachment.verify(
                    reference, contentHash, observed.getReference(),
                    observed.getContentVersion(), observed.getMediaType(),
                    observed.getSize(), observed.isDeleted()));
            return null;
        }

        @Override
        public Void uploadedConversation(
                String attachmentId, String contentHash) {
            WorkbenchStageUploadedConversationAttachment attachment =
                    stageAttachmentRepository.findById(attachmentId)
                            .orElseThrow(() -> new WorkbenchDomainException(
                                    WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                                    "Dynamic Stage uploaded attachment is unavailable"));
            uploadedAttachments.add(attachment.verifyForRun(
                    plan.uploadedAttachmentBinding(), contentHash,
                    verifiedAt));
            return null;
        }

        private VerifiedWorkbenchStageRunAttachmentSet result() {
            return VerifiedWorkbenchStageRunAttachmentSet.of(
                    documents, uploadedAttachments);
        }
    }

    private static final class CapabilityPreparation {

        private final ResolvedCapabilityResolution resolution;
        private final ResolvedCommandBinding commandBinding;

        private CapabilityPreparation(
                ResolvedCapabilityResolution resolution,
                ResolvedCommandBinding commandBinding) {
            this.resolution = Objects.requireNonNull(
                    resolution, "Capability resolution");
            this.commandBinding = commandBinding;
        }
    }
}
