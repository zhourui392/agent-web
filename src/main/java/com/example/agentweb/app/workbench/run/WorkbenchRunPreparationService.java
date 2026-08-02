package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunIdGenerator;
import com.example.agentweb.app.runtime.port.CredentialReference;
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
import com.example.agentweb.app.workbench.port.WorkspaceSnapshotGateway;
import com.example.agentweb.app.workbench.port.WorkspaceDevelopmentContextGateway;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityBindingResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityOverrideResolution;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.PhaseConversationProvisioning;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRepository;
import com.example.agentweb.domain.workbench.PhaseHandoffRevision;
import com.example.agentweb.domain.workbench.PhaseHandoffRevisionRepository;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmationRepository;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.ReviewOpinionRepository;
import com.example.agentweb.domain.workbench.ResolvedCapabilityResolution;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.VerifiedUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachmentSet;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhaseHistory;
import com.example.agentweb.domain.workbench.WorkbenchRunPreparationPlan;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptComposer;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
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
 * 外部进程启动前准备 Workbench Run 的完整不可变候选。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class WorkbenchRunPreparationService {

    private static final SnapshotPurpose RUN_START_PURPOSE =
            SnapshotPurpose.of("WORKBENCH_RUN_START");

    private final WorkbenchRunAvailability availability;
    private final WorkbenchRepository workbenchRepository;
    private final SessionRepository sessionRepository;
    private final WorkbenchPhaseHistoryQuery historyQuery;
    private final PhaseCapabilityProfileCatalog profileCatalog;
    private final PhaseCapabilityConfigurationRepository configurationRepository;
    private final PhaseCapabilityBindingResolver capabilityBindingResolver;
    private final WorkspaceSnapshotIdGenerator workspaceSnapshotIdGenerator;
    private final WorkspaceSnapshotGateway workspaceSnapshotGateway;
    private final WorkspaceDevelopmentContextGateway developmentContextGateway;
    private final ScopedDocumentGateway documentGateway;
    private final UploadedConversationAttachmentRepository attachmentRepository;
    private final RuntimePreflightGateway runtimePreflightGateway;
    private final PhaseHandoffRepository handoffRepository;
    private final PhaseHandoffRevisionRepository handoffRevisionRepository;
    private final HandoffReceptionRepository receptionRepository;
    private final ReviewModifyConfirmationRepository confirmationRepository;
    private final ReviewOpinionRepository opinionRepository;
    private final ChatRunIdGenerator runIdGenerator;
    private final WorkbenchTelemetry telemetry;
    private final WorkbenchRunPreparationSettings settings;
    private final Clock clock;

    public WorkbenchRunPreparationService(
            WorkbenchRunAvailability availability,
            WorkbenchRepository workbenchRepository,
            SessionRepository sessionRepository,
            WorkbenchPhaseHistoryQuery historyQuery,
            PhaseCapabilityProfileCatalog profileCatalog,
            PhaseCapabilityConfigurationRepository configurationRepository,
            PhaseCapabilityBindingResolver capabilityBindingResolver,
            WorkspaceSnapshotIdGenerator workspaceSnapshotIdGenerator,
            WorkspaceSnapshotGateway workspaceSnapshotGateway,
            WorkspaceDevelopmentContextGateway developmentContextGateway,
            ScopedDocumentGateway documentGateway,
            UploadedConversationAttachmentRepository attachmentRepository,
            RuntimePreflightGateway runtimePreflightGateway,
            PhaseHandoffRepository handoffRepository,
            PhaseHandoffRevisionRepository handoffRevisionRepository,
            HandoffReceptionRepository receptionRepository,
            ReviewModifyConfirmationRepository confirmationRepository,
            ReviewOpinionRepository opinionRepository,
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
        this.profileCatalog = Objects.requireNonNull(
                profileCatalog, "profileCatalog");
        this.configurationRepository = Objects.requireNonNull(
                configurationRepository, "configurationRepository");
        this.capabilityBindingResolver = Objects.requireNonNull(
                capabilityBindingResolver, "capabilityBindingResolver");
        this.workspaceSnapshotIdGenerator = Objects.requireNonNull(
                workspaceSnapshotIdGenerator, "workspaceSnapshotIdGenerator");
        this.workspaceSnapshotGateway = Objects.requireNonNull(
                workspaceSnapshotGateway, "workspaceSnapshotGateway");
        this.developmentContextGateway = Objects.requireNonNull(
                developmentContextGateway, "developmentContextGateway");
        this.documentGateway = Objects.requireNonNull(
                documentGateway, "documentGateway");
        this.attachmentRepository = Objects.requireNonNull(
                attachmentRepository, "attachmentRepository");
        this.runtimePreflightGateway = Objects.requireNonNull(
                runtimePreflightGateway, "runtimePreflightGateway");
        this.handoffRepository = Objects.requireNonNull(
                handoffRepository, "handoffRepository");
        this.handoffRevisionRepository = Objects.requireNonNull(
                handoffRevisionRepository, "handoffRevisionRepository");
        this.receptionRepository = Objects.requireNonNull(
                receptionRepository, "receptionRepository");
        this.confirmationRepository = Objects.requireNonNull(
                confirmationRepository, "confirmationRepository");
        this.opinionRepository = Objects.requireNonNull(
                opinionRepository, "opinionRepository");
        this.runIdGenerator = Objects.requireNonNull(
                runIdGenerator, "runIdGenerator");
        this.telemetry = Objects.requireNonNull(
                telemetry, "telemetry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PreparedWorkbenchRun prepare(
            OwnerReference actor, SubmitWorkbenchRunCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        availability.requireAvailable(command.getRunMode());
        Instant preparedAt = clock.instant();
        Workbench workbench = requireOwnedWorkbench(
                actor, command);
        WorkbenchRunPreparationPlan plan = obscureOwner(() ->
                workbench.planRunPreparation(
                        command.getPhase(), command.getRunMode(),
                        command.getHandoffSourceVersion(),
                        command.getReviewConfirmationId(), actor,
                        command.getExpectedVersion()));
        PhaseConversationProvisioning conversation =
                plan.getConversation();
        requireTrustedSession(conversation);
        WorkbenchPhaseHistory history = Objects.requireNonNull(
                historyQuery.load(conversation),
                "workbench phase history");
        history.requireCurrent(conversation);
        WorkspaceDevelopmentContext developmentContext = Objects.requireNonNull(
                developmentContextGateway.inspect(plan.getRepositoryScope()),
                "workspace development context");
        plan.requireDevelopmentContext(developmentContext);

        ReviewModifyConfirmation reviewConfirmation = resolveReview(
                plan, actor, command.getMessage(), preparedAt);
        HandoffPreparation handoff = resolveHandoff(
                plan, actor, preparedAt);
        CapabilityPreparation capability = resolveCapabilityWithTelemetry(
                plan, developmentContext);
        VerifiedWorkbenchRunAttachmentSet verifiedAttachments =
                verifyAttachments(
                        plan, command.getAttachments(), preparedAt);

        String snapshotId = workspaceSnapshotIdGenerator.nextId();
        WorkspaceSnapshot workspaceSnapshot = workspaceSnapshotGateway.capture(
                snapshotId, plan.getRepositoryScope(), RUN_START_PURPOSE);
        requireRunStartSnapshot(snapshotId, workspaceSnapshot);

        RuntimeLimits limits = settings.getRuntimeLimits();
        SandboxMode sandboxMode = SandboxMode.valueOf(
                plan.getWorkspaceAccess().name());
        WorkspaceLayout workspaceLayout = new WorkspaceLayout(
                plan.getRepositoryScope().primaryRepository()
                        .getRepositoryRoot(),
                plan.getReadableRepositoryRoots(),
                plan.getWritableRepositoryRoots(), sandboxMode);
        RuntimeSelection runtimeSelection = new RuntimeSelection(
                plan.getAgentType(), RuntimeVersionPolicy.configured(),
                CredentialReference.systemConfiguration());
        RuntimePreflightRequest preflightRequest =
                new RuntimePreflightRequest(
                        runtimeSelection, workspaceLayout,
                        capability.binding);
        RuntimePreflightReport preflight = runtimePreflightGateway.inspect(
                preflightRequest);
        requireExactPreflight(
                plan, capability.binding, sandboxMode, preflight);
        RuntimeEnforcementSnapshot runtimeEnforcement =
                RuntimeEnforcementSnapshot.forRun(
                        preflight.getAgentType().name(),
                        preflight.getRuntimeVersion(),
                        plan.getRepositoryScope().getScopeHash(),
                        plan.getRepositoryScope().getPrimaryRepositoryKey(),
                        plan.getRunMode(), plan.getWritableRepositoryKeys(),
                        limits.getTimeout().getSeconds(),
                        limits.getMaxOutputBytes());

        com.example.agentweb.domain.workbench.PreparedWorkbenchPrompt prompt =
                WorkbenchRunPromptComposer.compose(
                        plan, capability.resolution, handoff.revision,
                        developmentContext, workspaceSnapshot, history,
                        verifiedAttachments, command.getMessage());
        ChatRunId runId = runIdGenerator.nextId();
        WorkbenchRunPromptPayload promptPayload = prompt.freezePayload(
                runId.getValue(), preparedAt);
        WorkbenchRunSnapshot runSnapshot = WorkbenchRunSnapshot.create(
                runId.getValue(), command.getWorkbenchId(),
                command.getPhase(), command.getIdempotencyKey(),
                command.getRequestHash(), command.getRunMode(),
                plan.getRepositoryScope(), workspaceSnapshot.reference(),
                capability.binding, capability.overrideVersion,
                handoff.snapshotReference, prompt.snapshots(),
                prompt.getPromptHash(), runtimeEnforcement,
                verifiedAttachments.getRepositoryDocuments(),
                verifiedAttachments.getUploadedAttachments(),
                reviewConfirmation, preparedAt);
        return PreparedWorkbenchRun.of(
                command, runSnapshot, workspaceSnapshot, promptPayload,
                reviewConfirmation, handoff.reception,
                verifiedAttachments);
    }

    private VerifiedWorkbenchRunAttachmentSet verifyAttachments(
            WorkbenchRunPreparationPlan plan,
            List<WorkbenchRunAttachmentReference> attachments,
            Instant observedAt) {
        AttachmentVerifier verifier = new AttachmentVerifier(
                plan.getRepositoryScope(),
                plan.uploadedAttachmentBinding(), observedAt);
        for (WorkbenchRunAttachmentReference attachment : attachments) {
            attachment.resolve(verifier);
        }
        return verifier.result();
    }

    private final class AttachmentVerifier
            implements WorkbenchRunAttachmentReference.Resolver<Void> {

        private final RepositoryScope repositoryScope;
        private final UploadedAttachmentBinding uploadedBinding;
        private final Instant observedAt;
        private final List<VerifiedWorkbenchRunAttachment> repositoryDocuments =
                new ArrayList<VerifiedWorkbenchRunAttachment>();
        private final List<VerifiedUploadedConversationAttachment> uploads =
                new ArrayList<VerifiedUploadedConversationAttachment>();

        private AttachmentVerifier(
                RepositoryScope repositoryScope,
                UploadedAttachmentBinding uploadedBinding,
                Instant observedAt) {
            this.repositoryScope = repositoryScope;
            this.uploadedBinding = uploadedBinding;
            this.observedAt = observedAt;
        }

        @Override
        public Void repositoryDocument(
                com.example.agentweb.domain.workbench.DocumentReference reference,
                String contentHash) {
            DocumentContentView observed = Objects.requireNonNull(
                    documentGateway.readContent(repositoryScope, reference),
                    "observed workbench run attachment");
            repositoryDocuments.add(VerifiedWorkbenchRunAttachment.verify(
                    reference, contentHash, observed.getReference(),
                    observed.getContentVersion(), observed.getMediaType(),
                    observed.getSize(), observed.isDeleted()));
            return null;
        }

        @Override
        public Void uploadedConversation(
                String attachmentId, String contentHash) {
            UploadedConversationAttachment attachment = attachmentRepository
                    .findById(attachmentId)
                    .orElseThrow(() -> new WorkbenchDomainException(
                            WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                            "uploaded attachment is unavailable"));
            uploads.add(attachment.verifyForRun(
                    uploadedBinding, contentHash, observedAt));
            return null;
        }

        private VerifiedWorkbenchRunAttachmentSet result() {
            return VerifiedWorkbenchRunAttachmentSet.of(
                    repositoryDocuments, uploads);
        }
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, SubmitWorkbenchRunCommand command) {
        Workbench workbench = workbenchRepository.findById(
                        command.getWorkbenchId())
                .orElseThrow(WorkbenchNotFoundException::new);
        return obscureOwner(() -> {
            workbench.requireOwnedBy(actor);
            return workbench;
        });
    }

    private void requireTrustedSession(
            PhaseConversationProvisioning provisioning) {
        String sessionId = provisioning.requireCurrentConversationId();
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
    }

    private ReviewModifyConfirmation resolveReview(
            WorkbenchRunPreparationPlan plan, OwnerReference actor,
            String candidateMessage, Instant preparedAt) {
        if (!plan.requiresReviewConfirmation()) {
            return plan.requireReviewProof(
                    null, null, actor, preparedAt);
        }
        ReviewModifyConfirmation confirmation = confirmationRepository
                .findById(plan.getReviewConfirmationId())
                .orElseThrow(() -> new WorkbenchDomainException(
                        WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                        "review confirmation is unavailable"));
        ReviewOpinion opinion = opinionRepository.find(
                        plan.getWorkbenchId(),
                        confirmation.getOpinionVersion())
                .orElseThrow(() -> WorkbenchDomainException
                        .runBindingCorrupted());
        ReviewModifyConfirmation exactConfirmation =
                plan.requireReviewProof(
                confirmation, opinion, actor, preparedAt);
        opinion.requireExactContent(candidateMessage);
        return exactConfirmation;
    }

    private HandoffPreparation resolveHandoff(
            WorkbenchRunPreparationPlan plan, OwnerReference actor,
            Instant preparedAt) {
        if (!plan.requiresHandoff()) {
            return HandoffPreparation.none(
                    plan.handoffSnapshotReference(null));
        }
        HandoffReception existing = receptionRepository.find(
                        plan.getWorkbenchId(), plan.getPhase(),
                        plan.getHandoffSourcePhase())
                .orElse(null);
        PhaseHandoff latest = handoffRepository.find(
                                plan.getWorkbenchId(),
                                plan.getHandoffSourcePhase())
                        .orElse(null);
        HandoffReception reception = plan.resolveHandoffReception(
                existing, latest, actor, preparedAt);
        PhaseHandoffRevision revision = handoffRevisionRepository.findExact(
                        plan.getWorkbenchId(),
                        reception.getSourcePhase(),
                        reception.getSourceVersion(),
                        reception.getSourceHash())
                .orElseThrow(() -> WorkbenchDomainException
                        .runBindingCorrupted());
        plan.requireExactHandoffRevision(reception, revision);
        return HandoffPreparation.accepted(
                reception, revision,
                plan.handoffSnapshotReference(reception));
    }

    private CapabilityPreparation resolveCapability(
            WorkbenchRunPreparationPlan plan,
            WorkspaceDevelopmentContext developmentContext) {
        PhaseCapabilityProfile profile = profileCatalog.requireProfile(
                plan.getPhase());
        plan.requireProfile(profile);
        PhaseCapabilityConfiguration configuration =
                configurationRepository.find(
                                plan.getWorkbenchId(), plan.getPhase())
                        .orElse(null);
        PhaseCapabilityOverrideResolution overrideResolution =
                plan.resolveCapabilityOverride(
                profile, configuration);
        ResolvedCapabilityResolution resolution =
                capabilityBindingResolver.resolveForRun(
                profile, overrideResolution.getEffectiveOverride(),
                plan.capabilityPolicy(
                        settings.getCapabilityPolicyVersion(),
                        settings.getRuntimeCompatibility(),
                        settings.getAllowedSkillTrustSources(),
                        developmentContext));
        return new CapabilityPreparation(
                resolution,
                plan.capabilityOverrideVersion(configuration));
    }

    private CapabilityPreparation resolveCapabilityWithTelemetry(
            WorkbenchRunPreparationPlan plan,
            WorkspaceDevelopmentContext developmentContext) {
        CapabilityPreparation capability;
        try {
            capability = resolveCapability(plan, developmentContext);
        } catch (RuntimeException failure) {
            telemetry.capabilityResolution("FAILED");
            throw failure;
        }
        telemetry.capabilityResolution("SUCCESS");
        return capability;
    }

    private void requireRunStartSnapshot(
            String snapshotId, WorkspaceSnapshot snapshot) {
        if (snapshot == null
                || !snapshotId.equals(snapshot.getSnapshotId())
                || !RUN_START_PURPOSE.equals(snapshot.getPurpose())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    private void requireExactPreflight(
            WorkbenchRunPreparationPlan plan,
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
                    "runtime preflight facts do not match the prepared run");
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

    private static final class CapabilityPreparation {

        private final ResolvedCapabilityBinding binding;
        private final ResolvedCapabilityResolution resolution;
        private final Long overrideVersion;

        private CapabilityPreparation(
                ResolvedCapabilityResolution resolution,
                Long overrideVersion) {
            this.resolution = Objects.requireNonNull(
                    resolution, "capability resolution");
            this.binding = resolution.getBinding();
            this.overrideVersion = overrideVersion;
        }
    }

    private static final class HandoffPreparation {

        private final HandoffReception reception;
        private final PhaseHandoffRevision revision;
        private final com.example.agentweb.domain.workbench.HandoffSnapshotReference
                snapshotReference;

        private HandoffPreparation(
                HandoffReception reception,
                PhaseHandoffRevision revision,
                com.example.agentweb.domain.workbench.HandoffSnapshotReference
                        snapshotReference) {
            this.reception = reception;
            this.revision = revision;
            this.snapshotReference = snapshotReference;
        }

        private static HandoffPreparation none(
                com.example.agentweb.domain.workbench.HandoffSnapshotReference
                        snapshotReference) {
            return new HandoffPreparation(null, null, snapshotReference);
        }

        private static HandoffPreparation accepted(
                HandoffReception reception,
                PhaseHandoffRevision revision,
                com.example.agentweb.domain.workbench.HandoffSnapshotReference
                        snapshotReference) {
            return new HandoffPreparation(
                    reception, revision, snapshotReference);
        }
    }
}
