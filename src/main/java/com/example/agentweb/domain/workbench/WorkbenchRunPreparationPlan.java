package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Workbench 聚合冻结的单次 Run 准备要求。
 *
 * <p>Phase、RunMode、Handoff、Review 和可写根的组合规则全部在此收敛；
 * Application 只消费计划并编排外部端口。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunPreparationPlan {

    public enum WorkspaceAccess {
        READ_ONLY,
        WORKSPACE_WRITE
    }

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final RunMode runMode;
    private final AgentType agentType;
    private final String environment;
    private final String title;
    private final String originalGoal;
    private final RepositoryScope repositoryScope;
    private final PhaseConversationProvisioning conversation;
    private final WorkbenchPhase handoffSourcePhase;
    private final Long handoffSourceVersion;
    private final String reviewConfirmationId;
    private final List<String> readableRepositoryRoots;
    private final List<String> writableRepositoryRoots;
    private final List<String> writableRepositoryKeys;
    private final WorkspaceAccess workspaceAccess;

    private WorkbenchRunPreparationPlan(
            Workbench workbench, WorkbenchPhase phase, RunMode runMode,
            Long handoffSourceVersion, String reviewConfirmationId,
            PhaseConversationProvisioning conversation) {
        if (workbench == null || phase == null || runMode == null
                || conversation == null) {
            throw new IllegalArgumentException(
                    "workbench run preparation facts must be complete");
        }
        boolean reviewProofPresent = reviewConfirmationId != null;
        PhaseRunPolicy.requireAllowedWithPersistedReviewProof(
                phase, runMode, reviewProofPresent);
        if (workbench.getAgentType() == AgentType.NATIVE) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "NATIVE diagnosis runtime is unavailable to Workbench");
        }
        this.workbenchId = workbench.getId();
        this.phase = phase;
        this.runMode = runMode;
        this.agentType = workbench.getAgentType();
        this.environment = workbench.getEnvironment();
        this.title = workbench.getTitle();
        this.originalGoal = workbench.getOriginalGoal();
        this.repositoryScope = workbench.getRepositoryScope();
        this.conversation = conversation;
        this.handoffSourcePhase = phase.defaultHandoffSource().orElse(null);
        this.handoffSourceVersion = requireHandoffVersion(
                this.handoffSourcePhase, handoffSourceVersion);
        this.reviewConfirmationId = reviewConfirmationId;
        this.readableRepositoryRoots = repositoryRoots(repositoryScope);
        if (runMode.modifiesWorkspace()) {
            this.writableRepositoryRoots = readableRepositoryRoots;
            this.writableRepositoryKeys = repositoryKeys(repositoryScope);
            this.workspaceAccess = WorkspaceAccess.WORKSPACE_WRITE;
        } else {
            this.writableRepositoryRoots = Collections.emptyList();
            this.writableRepositoryKeys = Collections.emptyList();
            this.workspaceAccess = WorkspaceAccess.READ_ONLY;
        }
    }

    static WorkbenchRunPreparationPlan plan(
            Workbench workbench, WorkbenchPhase phase, RunMode runMode,
            Long handoffSourceVersion, String reviewConfirmationId,
            PhaseConversationProvisioning conversation) {
        return new WorkbenchRunPreparationPlan(
                workbench, phase, runMode, handoffSourceVersion,
                reviewConfirmationId, conversation);
    }

    public boolean requiresHandoff() {
        return handoffSourcePhase != null;
    }

    public boolean requiresReviewConfirmation() {
        return reviewConfirmationId != null;
    }

    public HandoffReception acceptLatestHandoff(
            PhaseHandoff latest, OwnerReference actor, Instant acceptedAt) {
        requireHandoffIdentity(latest);
        if (latest.getVersion() != handoffSourceVersion.longValue()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "requested handoff version is stale");
        }
        return HandoffReception.accept(
                workbenchId, phase, handoffSourcePhase,
                latest.getVersion(), latest.getContentHash(), actor,
                acceptedAt);
    }

    public HandoffReception requireAcceptedHandoff(
            HandoffReception reception) {
        if (!requiresHandoff() || reception == null
                || !workbenchId.equals(reception.getWorkbenchId())
                || phase != reception.getTargetPhase()
                || handoffSourcePhase != reception.getSourcePhase()
                || handoffSourceVersion.longValue()
                != reception.getSourceVersion()) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return reception;
    }

    public PhaseHandoffRevision requireExactHandoffRevision(
            HandoffReception reception, PhaseHandoffRevision revision) {
        requireAcceptedHandoff(reception);
        if (revision == null
                || !workbenchId.equals(revision.getWorkbenchId())
                || handoffSourcePhase != revision.getSourcePhase()
                || reception.getSourceVersion() != revision.getVersion()
                || !reception.getSourceHash().equals(
                revision.getContentHash())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return revision;
    }

    public HandoffSnapshotReference handoffSnapshotReference(
            HandoffReception reception) {
        if (!requiresHandoff()) {
            if (reception != null) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
            return null;
        }
        HandoffReception accepted = requireAcceptedHandoff(reception);
        return HandoffSnapshotReference.of(
                accepted.getSourcePhase(), accepted.getSourceVersion(),
                accepted.getSourceHash());
    }

    public ReviewModifyConfirmation requireReviewProof(
            ReviewModifyConfirmation confirmation, ReviewOpinion opinion,
            OwnerReference actor, Instant preparedAt) {
        if (!requiresReviewConfirmation()) {
            if (confirmation != null || opinion != null) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
            return null;
        }
        if (confirmation == null || opinion == null
                || !reviewConfirmationId.equals(
                confirmation.getConfirmationId())
                || !confirmation.getOpinion().equals(opinion)
                || !confirmation.isValidFor(
                workbenchId, actor, preparedAt)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "review confirmation must bind the exact opinion and owner");
        }
        return confirmation;
    }

    public PhaseCapabilityResolutionPolicy capabilityPolicy(
            String policyVersion, String runtimeCompatibility,
            Set<SkillTrustSource> allowedSkillTrustSources) {
        return PhaseCapabilityResolutionPolicy.forRun(
                policyVersion, runMode, agentType.name(),
                runtimeCompatibility,
                allowedSkillTrustSources);
    }

    public CapabilityOverride capabilityOverride(
            PhaseCapabilityProfile profile,
            PhaseCapabilityConfiguration configuration) {
        requireProfile(profile);
        if (configuration == null) {
            return CapabilityOverride.empty();
        }
        if (!workbenchId.equals(configuration.getWorkbenchId())
                || phase != configuration.getPhase()
                || !profile.getProfileId().equals(
                configuration.getBaseProfileId())
                || !profile.getProfileVersion().equals(
                configuration.getBaseProfileVersion())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return configuration.getOverride();
    }

    public Long capabilityOverrideVersion(
            PhaseCapabilityConfiguration configuration) {
        return configuration == null
                ? null : Long.valueOf(configuration.getVersion());
    }

    public void requireProfile(PhaseCapabilityProfile profile) {
        if (profile == null || profile.getPhase() != phase) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    private void requireHandoffIdentity(PhaseHandoff latest) {
        if (!requiresHandoff() || latest == null
                || !workbenchId.equals(latest.getWorkbenchId())
                || handoffSourcePhase != latest.getSourcePhase()) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    private static Long requireHandoffVersion(
            WorkbenchPhase sourcePhase, Long requestedVersion) {
        if (sourcePhase == null) {
            if (requestedVersion != null) {
                throw new WorkbenchDomainException(
                        WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                        "requirement analysis must not select an upstream handoff");
            }
            return null;
        }
        if (requestedVersion == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                    "downstream phase run requires an accepted handoff version");
        }
        if (requestedVersion.longValue() < 0L) {
            throw new IllegalArgumentException(
                    "handoff source version must not be negative");
        }
        return requestedVersion;
    }

    private static List<String> repositoryRoots(RepositoryScope scope) {
        List<String> roots = new ArrayList<String>();
        for (ResolvedRepository repository : scope.getRepositories()) {
            roots.add(repository.getRepositoryRoot());
        }
        return Collections.unmodifiableList(roots);
    }

    private static List<String> repositoryKeys(RepositoryScope scope) {
        List<String> keys = new ArrayList<String>();
        for (ResolvedRepository repository : scope.getRepositories()) {
            keys.add(repository.getRepositoryKey());
        }
        return Collections.unmodifiableList(keys);
    }
}
