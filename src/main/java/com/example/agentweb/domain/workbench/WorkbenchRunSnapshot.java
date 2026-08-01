package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部进程启动前固化的 Workbench 单次运行快照。
 *
 * <p>本聚合创建后不可变，且有意不包含 ChatRun 终态。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunSnapshot {

    private final String runId;
    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String submissionIdempotencyKey;
    private final String submissionRequestHash;
    private final RunMode runMode;
    private final String repositoryScopeHash;
    private final WorkspaceSnapshotReference workspaceSnapshotReference;
    private final ResolvedCapabilityBinding capabilityBinding;
    private final Long overrideVersion;
    private final HandoffSnapshotReference handoffSource;
    private final List<PromptPartSnapshot> promptParts;
    private final String promptHash;
    private final List<VerifiedWorkbenchRunAttachment> verifiedAttachments;
    private final RuntimeEnforcementSnapshot runtimeEnforcement;
    private final String reviewConfirmationId;
    private final Long reviewOpinionVersion;
    private final String reviewOpinionHash;
    private final Instant createdAt;

    private WorkbenchRunSnapshot(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey, String submissionRequestHash,
            RunMode runMode, RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding, Long overrideVersion,
            HandoffSnapshotReference handoffSource,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments,
            ReviewModifyConfirmation reviewConfirmation, Instant createdAt) {
        this(runId, workbenchId, phase,
                submissionIdempotencyKey, submissionRequestHash,
                runMode, repositoryScope,
                workspaceSnapshotReference, capabilityBinding, overrideVersion,
                handoffSource, promptParts, promptHash, runtimeEnforcement,
                verifiedAttachments,
                reviewConfirmation == null ? null
                        : reviewConfirmation.getConfirmationId(),
                reviewConfirmation == null ? null
                        : Long.valueOf(reviewConfirmation.getOpinionVersion()),
                reviewConfirmation == null ? null
                        : reviewConfirmation.getOpinionHash(),
                createdAt);
        if (reviewConfirmation != null
                && !reviewConfirmation.isValidFor(
                workbenchId, reviewConfirmation.getConfirmedBy(), createdAt)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "review confirmation does not bind this workbench and snapshot time");
        }
    }

    private WorkbenchRunSnapshot(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey, String submissionRequestHash,
            RunMode runMode, RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding, Long overrideVersion,
            HandoffSnapshotReference handoffSource,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments,
            String reviewConfirmationId, Long reviewOpinionVersion,
            String reviewOpinionHash, Instant createdAt) {
        this.runId = DomainText.require(runId, "workbench snapshot run id", 128);
        if (workbenchId == null || phase == null || runMode == null
                || repositoryScope == null || workspaceSnapshotReference == null
                || capabilityBinding == null || runtimeEnforcement == null) {
            throw new IllegalArgumentException(
                    "workbench run snapshot required values must not be null");
        }
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.submissionIdempotencyKey = DomainText.require(
                submissionIdempotencyKey,
                "workbench run submission idempotency key", 128);
        this.submissionRequestHash = DomainText.requireSha256(
                submissionRequestHash,
                "workbench run submission request hash");
        this.runMode = runMode;
        boolean reviewProofPresent = requireConsistentReviewProof(
                reviewConfirmationId, reviewOpinionVersion, reviewOpinionHash);
        PhaseRunPolicy.requireAllowedWithPersistedReviewProof(
                phase, runMode, reviewProofPresent);
        this.repositoryScopeHash = repositoryScope.getScopeHash();
        this.workspaceSnapshotReference = workspaceSnapshotReference;
        requireWorkspaceSnapshotMatchesScope(repositoryScope, workspaceSnapshotReference);
        this.capabilityBinding = capabilityBinding;
        if (overrideVersion != null && overrideVersion.longValue() < 0L) {
            throw new IllegalArgumentException(
                    "capability override version must not be negative");
        }
        this.overrideVersion = overrideVersion;
        this.handoffSource = requireHandoffMatchesPhase(phase, handoffSource);
        this.promptParts = immutablePromptParts(promptParts);
        this.promptHash = DomainText.requireSha256(promptHash, "workbench prompt hash");
        this.verifiedAttachments =
                VerifiedWorkbenchRunAttachment.immutableListForScope(
                        verifiedAttachments, repositoryScope);
        this.runtimeEnforcement = runtimeEnforcement;
        requireRuntimeMatchesScope(runMode, repositoryScope, runtimeEnforcement);
        this.reviewConfirmationId = reviewProofPresent
                ? DomainText.require(
                reviewConfirmationId, "review confirmation id", 128) : null;
        this.reviewOpinionVersion = reviewProofPresent
                ? requirePositiveOpinionVersion(reviewOpinionVersion) : null;
        this.reviewOpinionHash = reviewProofPresent
                ? DomainText.requireSha256(
                reviewOpinionHash, "review opinion content hash") : null;
        this.createdAt = DomainText.requireTime(createdAt, "run snapshot created at");
    }

    public static WorkbenchRunSnapshot create(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey, String submissionRequestHash,
            RunMode runMode, RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding, Long overrideVersion,
            HandoffSnapshotReference handoffSource,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            ReviewModifyConfirmation reviewConfirmation, Instant createdAt) {
        return create(
                runId, workbenchId, phase,
                submissionIdempotencyKey, submissionRequestHash,
                runMode, repositoryScope, workspaceSnapshotReference,
                capabilityBinding, overrideVersion, handoffSource,
                promptParts, promptHash, runtimeEnforcement,
                Collections.<VerifiedWorkbenchRunAttachment>emptyList(),
                reviewConfirmation, createdAt);
    }

    public static WorkbenchRunSnapshot create(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey, String submissionRequestHash,
            RunMode runMode, RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding, Long overrideVersion,
            HandoffSnapshotReference handoffSource,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments,
            ReviewModifyConfirmation reviewConfirmation, Instant createdAt) {
        return new WorkbenchRunSnapshot(
                runId, workbenchId, phase,
                submissionIdempotencyKey, submissionRequestHash,
                runMode, repositoryScope,
                workspaceSnapshotReference, capabilityBinding, overrideVersion,
                handoffSource, promptParts, promptHash, runtimeEnforcement,
                verifiedAttachments, reviewConfirmation, createdAt);
    }

    public static WorkbenchRunSnapshot restore(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey, String submissionRequestHash,
            RunMode runMode, RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding, Long overrideVersion,
            HandoffSnapshotReference handoffSource,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            String reviewConfirmationId, Long reviewOpinionVersion,
            String reviewOpinionHash, Instant createdAt) {
        return restore(
                runId, workbenchId, phase,
                submissionIdempotencyKey, submissionRequestHash,
                runMode, repositoryScope, workspaceSnapshotReference,
                capabilityBinding, overrideVersion, handoffSource,
                promptParts, promptHash, runtimeEnforcement,
                Collections.<VerifiedWorkbenchRunAttachment>emptyList(),
                reviewConfirmationId, reviewOpinionVersion,
                reviewOpinionHash, createdAt);
    }

    public static WorkbenchRunSnapshot restore(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey, String submissionRequestHash,
            RunMode runMode, RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding, Long overrideVersion,
            HandoffSnapshotReference handoffSource,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments,
            String reviewConfirmationId, Long reviewOpinionVersion,
            String reviewOpinionHash, Instant createdAt) {
        return new WorkbenchRunSnapshot(
                runId, workbenchId, phase,
                submissionIdempotencyKey, submissionRequestHash,
                runMode, repositoryScope,
                workspaceSnapshotReference, capabilityBinding, overrideVersion,
                handoffSource, promptParts, promptHash, runtimeEnforcement,
                verifiedAttachments,
                reviewConfirmationId, reviewOpinionVersion,
                reviewOpinionHash, createdAt);
    }

    /**
     * 验证 Phase 级幂等键是否仍绑定同一规范化请求。
     *
     * @return 已绑定的 Run ID
     */
    public String requireReplay(
            WorkbenchId candidateWorkbenchId, WorkbenchPhase candidatePhase,
            String candidateIdempotencyKey, String candidateRequestHash) {
        String key = DomainText.require(
                candidateIdempotencyKey,
                "workbench run submission idempotency key", 128);
        String hash = DomainText.requireSha256(
                candidateRequestHash,
                "workbench run submission request hash");
        if (!workbenchId.equals(candidateWorkbenchId)
                || phase != candidatePhase
                || !submissionIdempotencyKey.equals(key)
                || !submissionRequestHash.equals(hash)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "workbench run idempotency key belongs to another request");
        }
        return runId;
    }

    /**
     * 验证快速重放候选仍属于当前 Owner、Workbench 与规范化请求。
     *
     * @return 已绑定的 Run ID
     */
    public String requireReplay(
            Workbench candidateWorkbench, OwnerReference candidateOwner,
            WorkbenchPhase candidatePhase,
            String candidateIdempotencyKey, String candidateRequestHash) {
        if (candidateWorkbench == null) {
            throw new IllegalArgumentException(
                    "workbench is required for owner-scoped run replay");
        }
        candidateWorkbench.requireOwnedBy(candidateOwner);
        return requireReplay(
                candidateWorkbench.getId(), candidatePhase,
                candidateIdempotencyKey, candidateRequestHash);
    }

    /**
     * 要求私有 Prompt 正文与当前 Snapshot 的 Run、Hash 和冻结时间完全一致。
     */
    public void requirePromptPayload(WorkbenchRunPromptPayload payload) {
        if (payload == null
                || !runId.equals(payload.getRunId())
                || !promptHash.equals(payload.getPromptHash())
                || !createdAt.equals(payload.getCreatedAt())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    /**
     * 返回与 ChatRun 来源绑定同源的稳定 Workbench 执行引用。
     */
    public String executionOriginReference() {
        return workbenchId.getValue() + ":" + phase.name();
    }

    /**
     * 要求首次提交携带的 Workspace Snapshot 是准备阶段观察到的 exact Run-start 事实。
     */
    public void requireWorkspaceSnapshot(WorkspaceSnapshot snapshot) {
        if (snapshot == null
                || !"WORKBENCH_RUN_START".equals(
                snapshot.getPurpose().getValue())
                || !workspaceSnapshotReference.equals(
                snapshot.reference())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    /**
     * 以 Snapshot 冻结的 Runtime 规则投影安全仓库范围；绝对路径不会进入结果。
     */
    public List<RunRepositoryScopeFact> repositoryScopeFacts(
            RepositoryScope repositoryScope) {
        if (repositoryScope == null
                || !repositoryScopeHash.equals(
                repositoryScope.getScopeHash())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return runtimeEnforcement.repositoryScopeFacts(repositoryScope);
    }

    /**
     * 要求首次下游 Run 同事务保存的 Reception 与 Snapshot 上游版本完全一致。
     */
    public void requireHandoffReception(HandoffReception reception) {
        if (handoffSource == null) {
            if (reception != null) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
            return;
        }
        if (reception == null
                || !workbenchId.equals(reception.getWorkbenchId())
                || phase != reception.getTargetPhase()
                || handoffSource.getSourcePhase()
                != reception.getSourcePhase()
                || handoffSource.getSourceVersion()
                != reception.getSourceVersion()
                || !handoffSource.getSourceHash()
                .equals(reception.getSourceHash())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    /**
     * 要求 Review 写运行携带的人工确认与 Snapshot 冻结证明完全一致。
     */
    public void requireReviewConfirmation(
            ReviewModifyConfirmation confirmation) {
        if (reviewConfirmationId == null) {
            if (confirmation != null) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
            return;
        }
        if (confirmation == null
                || !reviewConfirmationId.equals(
                confirmation.getConfirmationId())
                || reviewOpinionVersion.longValue()
                != confirmation.getOpinionVersion()
                || !reviewOpinionHash.equals(
                confirmation.getOpinionHash())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    /**
     * 使用快照冻结的 Workbench、Phase 与 Run 事实完成首次终态严格释放。
     */
    public void finishRequiredRun(
            Workbench workbench, String candidateRunId, Instant terminalAt) {
        String candidate = DomainText.require(
                candidateRunId, "candidate workbench run id", 128);
        if (workbench == null
                || !runId.equals(candidate)
                || !workbenchId.equals(workbench.getId())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        workbench.finishRequiredRun(phase, runId, terminalAt);
    }

    /**
     * 要求 URL 中的 Workbench/Run、不可变 Snapshot 与 ChatRun 来源绑定完全一致。
     *
     * <p>任何不一致都按 Run 不可见处理，调用方不得根据失败原因枚举其他
     * Workbench 或 Run。</p>
     */
    public void requireExactRun(
            Workbench workbench, ChatRun run, String candidateRunId) {
        String candidate;
        try {
            candidate = DomainText.require(
                    candidateRunId, "candidate workbench run id", 128);
        } catch (IllegalArgumentException failure) {
            throw new ChatRunNotFoundException("unavailable");
        }
        if (workbench == null || run == null
                || !workbenchId.equals(workbench.getId())
                || !repositoryScopeHash.equals(
                workbench.getRepositoryScope().getScopeHash())
                || !runId.equals(candidate)
                || !runId.equals(run.getId().getValue())) {
            throw new ChatRunNotFoundException(candidate);
        }
        run.requireWorkbenchExecutionContext(executionOriginReference());
    }

    private static void requireWorkspaceSnapshotMatchesScope(
            RepositoryScope scope, WorkspaceSnapshotReference snapshotReference) {
        if (!scope.matchesSnapshotTopology(snapshotReference)
                || scope.repositoryCount() != snapshotReference.getRepositoryCount()) {
            throw new IllegalArgumentException(
                    "workspace snapshot must match the frozen repository scope");
        }
    }

    private static HandoffSnapshotReference requireHandoffMatchesPhase(
            WorkbenchPhase phase, HandoffSnapshotReference handoffSource) {
        WorkbenchPhase expectedSource = phase.defaultHandoffSource().orElse(null);
        if (expectedSource == null) {
            if (handoffSource != null) {
                throw new IllegalArgumentException(
                        "requirement analysis must not have an upstream handoff");
            }
            return null;
        }
        if (handoffSource == null || handoffSource.getSourcePhase() != expectedSource) {
            throw new IllegalArgumentException(
                    "run snapshot must freeze the phase default upstream handoff");
        }
        return handoffSource;
    }

    private static List<PromptPartSnapshot> immutablePromptParts(
            List<PromptPartSnapshot> parts) {
        if (parts == null || parts.isEmpty() || parts.contains(null)) {
            throw new IllegalArgumentException(
                    "run snapshot must contain prompt part hashes");
        }
        return Collections.unmodifiableList(new ArrayList<PromptPartSnapshot>(parts));
    }

    private static void requireRuntimeMatchesScope(
            RunMode runMode, RepositoryScope scope,
            RuntimeEnforcementSnapshot runtime) {
        if (runtime.getRunMode() != runMode
                || !scope.getScopeHash().equals(runtime.getRepositoryScopeHash())
                || !scope.getPrimaryRepositoryKey().equals(
                runtime.getPrimaryRepositoryKey())) {
            throw new IllegalArgumentException(
                    "runtime enforcement must match run mode and repository scope");
        }
        Set<String> selected = new HashSet<String>();
        for (ResolvedRepository repository : scope.getRepositories()) {
            selected.add(repository.getRepositoryKey());
        }
        Set<String> writable = new HashSet<String>(
                runtime.getWritableRepositoryKeys());
        if (runMode.modifiesWorkspace() && !selected.equals(writable)
                || !runMode.modifiesWorkspace() && !writable.isEmpty()) {
            throw new IllegalArgumentException(
                    "runtime writable repositories must exactly match run mode and scope");
        }
    }

    private static boolean requireConsistentReviewProof(
            String confirmationId, Long opinionVersion, String opinionHash) {
        boolean allAbsent = confirmationId == null
                && opinionVersion == null && opinionHash == null;
        boolean allPresent = confirmationId != null
                && opinionVersion != null && opinionHash != null;
        if (!allAbsent && !allPresent) {
            throw new IllegalArgumentException(
                    "review confirmation snapshot fields must be all present or all absent");
        }
        return allPresent;
    }

    private static Long requirePositiveOpinionVersion(Long version) {
        if (version.longValue() < 1L) {
            throw new IllegalArgumentException(
                    "review opinion version must be positive");
        }
        return version;
    }
}
