package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
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
import java.util.regex.Pattern;

/**
 * 动态 Workbench Stage 单次运行的不可变执行快照。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageRunSnapshot {

    private static final Pattern STAGE_IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final String runId;
    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final String stageDefinitionIdentifier;
    private final long stageDefinitionRevision;
    private final String stageSnapshotHash;
    private final String submissionIdempotencyKey;
    private final String submissionRequestHash;
    private final RunMode runMode;
    private final String repositoryScopeHash;
    private final WorkspaceSnapshotReference workspaceSnapshotReference;
    private final ResolvedCapabilityBinding capabilityBinding;
    private final ResolvedCommandBinding commandBinding;
    private final long contextVersion;
    private final String contextHash;
    private final List<WorkbenchContextDocumentSnapshot>
            contextDocumentReferences;
    private final List<PromptPartSnapshot> promptParts;
    private final String promptHash;
    private final RuntimeEnforcementSnapshot runtimeEnforcement;
    private final List<VerifiedWorkbenchRunAttachment> verifiedAttachments;
    private final List<VerifiedWorkbenchStageUploadedConversationAttachment>
            verifiedUploadedAttachments;
    private final Instant createdAt;

    private WorkbenchStageRunSnapshot(
            String runId, WorkbenchId workbenchId,
            String stageInstanceIdentifier,
            WorkbenchStageSnapshot stageSnapshot,
            String submissionIdempotencyKey,
            String submissionRequestHash, RunMode runMode,
            RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding,
            ResolvedCommandBinding commandBinding,
            long contextVersion, String contextHash,
            List<WorkbenchContextDocumentSnapshot> contextDocumentReferences,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments,
            List<VerifiedWorkbenchStageUploadedConversationAttachment>
                    verifiedUploadedAttachments,
            Instant createdAt) {
        this.runId = DomainText.require(
                runId, "Workbench Stage Run identifier", 128);
        if (workbenchId == null || stageSnapshot == null || runMode == null
                || repositoryScope == null
                || workspaceSnapshotReference == null
                || capabilityBinding == null
                || runtimeEnforcement == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run Snapshot facts are required");
        }
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = requireStageIdentifier(
                stageInstanceIdentifier);
        this.stageDefinitionIdentifier =
                stageSnapshot.getDefinitionIdentifier();
        this.stageDefinitionRevision = stageSnapshot.getDefinitionRevision();
        this.stageSnapshotHash = stageSnapshot.getSnapshotHash();
        stageSnapshot.requireRunModeAllowed(runMode);
        this.submissionIdempotencyKey = DomainText.require(
                submissionIdempotencyKey,
                "Workbench Stage Run idempotency key", 128);
        this.submissionRequestHash = DomainText.requireSha256(
                submissionRequestHash,
                "Workbench Stage Run submission request Hash");
        this.runMode = runMode;
        this.repositoryScopeHash = repositoryScope.getScopeHash();
        this.workspaceSnapshotReference = workspaceSnapshotReference;
        requireWorkspaceSnapshotMatchesScope(
                repositoryScope, workspaceSnapshotReference);
        this.capabilityBinding = capabilityBinding;
        this.commandBinding = commandBinding;
        if (contextVersion < 0L) {
            throw new IllegalArgumentException(
                    "Workbench Context version must not be negative");
        }
        this.contextVersion = contextVersion;
        this.contextHash = DomainText.requireSha256(
                contextHash, "Workbench Context Hash");
        this.contextDocumentReferences = immutable(
                contextDocumentReferences,
                "Workbench Context Document references", true);
        this.promptParts = immutable(
                promptParts, "Workbench Stage Run prompt parts", false);
        this.promptHash = DomainText.requireSha256(
                promptHash, "Workbench Stage Run prompt Hash");
        this.runtimeEnforcement = runtimeEnforcement;
        requireRuntimeMatchesScope(
                runMode, repositoryScope, runtimeEnforcement);
        VerifiedWorkbenchStageRunAttachmentSet attachmentSet =
                VerifiedWorkbenchStageRunAttachmentSet.of(
                        verifiedAttachments, verifiedUploadedAttachments);
        this.verifiedAttachments =
                VerifiedWorkbenchRunAttachment.immutableListForScope(
                        attachmentSet.getRepositoryDocuments(), repositoryScope);
        this.verifiedUploadedAttachments =
                attachmentSet.getUploadedAttachments();
        requireUploadedAttachmentBindings();
        this.createdAt = DomainText.requireTime(
                createdAt, "Workbench Stage Run Snapshot creation time");
    }

    public static WorkbenchStageRunSnapshot create(
            String runId, WorkbenchId workbenchId,
            String stageInstanceIdentifier,
            WorkbenchStageSnapshot stageSnapshot,
            String submissionIdempotencyKey,
            String submissionRequestHash, RunMode runMode,
            RepositoryScope repositoryScope,
            WorkspaceSnapshotReference workspaceSnapshotReference,
            ResolvedCapabilityBinding capabilityBinding,
            ResolvedCommandBinding commandBinding,
            long contextVersion, String contextHash,
            List<WorkbenchContextDocumentSnapshot> contextDocumentReferences,
            List<PromptPartSnapshot> promptParts, String promptHash,
            RuntimeEnforcementSnapshot runtimeEnforcement,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments,
            List<VerifiedWorkbenchStageUploadedConversationAttachment>
                    verifiedUploadedAttachments,
            Instant createdAt) {
        return new WorkbenchStageRunSnapshot(
                runId, workbenchId, stageInstanceIdentifier, stageSnapshot,
                submissionIdempotencyKey, submissionRequestHash, runMode,
                repositoryScope, workspaceSnapshotReference,
                capabilityBinding, commandBinding, contextVersion,
                contextHash, contextDocumentReferences, promptParts,
                promptHash, runtimeEnforcement, verifiedAttachments,
                verifiedUploadedAttachments, createdAt);
    }

    public String requireReplay(
            WorkbenchId candidateWorkbenchId,
            String candidateStageInstanceIdentifier,
            String candidateIdempotencyKey,
            String candidateRequestHash) {
        String stageIdentifier = requireStageIdentifier(
                candidateStageInstanceIdentifier);
        String key = DomainText.require(
                candidateIdempotencyKey,
                "Workbench Stage Run idempotency key", 128);
        String hash = DomainText.requireSha256(
                candidateRequestHash,
                "Workbench Stage Run submission request Hash");
        if (!workbenchId.equals(candidateWorkbenchId)
                || !stageInstanceIdentifier.equals(stageIdentifier)
                || !submissionIdempotencyKey.equals(key)
                || !submissionRequestHash.equals(hash)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "Workbench Stage Run idempotency key belongs to another request");
        }
        return runId;
    }

    public void requirePromptPayload(WorkbenchRunPromptPayload payload) {
        if (payload == null
                || !runId.equals(payload.getRunId())
                || !promptHash.equals(payload.getPromptHash())
                || !createdAt.equals(payload.getCreatedAt())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    public void requireWorkspaceSnapshot(WorkspaceSnapshot snapshot) {
        if (snapshot == null
                || !"WORKBENCH_RUN_START".equals(
                snapshot.getPurpose().getValue())
                || !workspaceSnapshotReference.equals(snapshot.reference())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    public String executionOriginReference() {
        return workbenchId.getValue() + ":" + stageInstanceIdentifier;
    }

    public List<RunRepositoryScopeFact> repositoryScopeFacts(
            RepositoryScope repositoryScope) {
        if (repositoryScope == null
                || !repositoryScopeHash.equals(
                repositoryScope.getScopeHash())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return runtimeEnforcement.repositoryScopeFacts(repositoryScope);
    }

    public void finishRequiredRun(
            Workbench workbench, String candidateRunId,
            Instant terminalAt) {
        String candidate = DomainText.require(
                candidateRunId, "Candidate Workbench Stage Run identifier", 128);
        if (!matchesWorkbenchStage(workbench) || !runId.equals(candidate)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        workbench.finishRequiredStageRun(
                stageInstanceIdentifier, runId, terminalAt);
    }

    public void requireExactRun(
            Workbench workbench, ChatRun run, String candidateRunId) {
        String candidate;
        try {
            candidate = DomainText.require(
                    candidateRunId,
                    "Candidate Workbench Stage Run identifier", 128);
        } catch (IllegalArgumentException failure) {
            throw new ChatRunNotFoundException("unavailable");
        }
        if (!matchesWorkbenchStage(workbench) || run == null
                || !runId.equals(candidate)
                || !runId.equals(run.getId().getValue())) {
            throw new ChatRunNotFoundException(candidate);
        }
        run.requireWorkbenchExecutionContext(executionOriginReference());
    }

    private boolean matchesWorkbenchStage(Workbench workbench) {
        if (workbench == null
                || !workbenchId.equals(workbench.getId())
                || !repositoryScopeHash.equals(
                workbench.getRepositoryScope().getScopeHash())) {
            return false;
        }
        try {
            WorkbenchStageState stage = workbench.stage(
                    stageInstanceIdentifier);
            return stageDefinitionIdentifier.equals(
                    stage.getSnapshot().getDefinitionIdentifier())
                    && stageDefinitionRevision
                    == stage.getSnapshot().getDefinitionRevision()
                    && stageSnapshotHash.equals(
                    stage.getSnapshot().getSnapshotHash());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void requireUploadedAttachmentBindings() {
        WorkbenchStageUploadedAttachmentBinding sharedBinding = null;
        for (VerifiedWorkbenchStageUploadedConversationAttachment attachment
                : verifiedUploadedAttachments) {
            WorkbenchStageUploadedAttachmentBinding binding =
                    attachment.getBinding();
            if (!workbenchId.equals(binding.getWorkbenchId())
                    || !stageInstanceIdentifier.equals(
                    binding.getStageInstanceIdentifier())
                    || sharedBinding != null
                    && !sharedBinding.equals(binding)) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
            sharedBinding = binding;
        }
    }

    private static void requireWorkspaceSnapshotMatchesScope(
            RepositoryScope scope,
            WorkspaceSnapshotReference snapshotReference) {
        if (!scope.matchesSnapshotTopology(snapshotReference)
                || scope.repositoryCount()
                != snapshotReference.getRepositoryCount()) {
            throw new IllegalArgumentException(
                    "Workspace Snapshot must match the frozen Repository Scope");
        }
    }

    private static void requireRuntimeMatchesScope(
            RunMode runMode, RepositoryScope scope,
            RuntimeEnforcementSnapshot runtime) {
        if (runtime.getRunMode() != runMode
                || !scope.getScopeHash().equals(
                runtime.getRepositoryScopeHash())
                || !scope.getPrimaryRepositoryKey().equals(
                runtime.getPrimaryRepositoryKey())) {
            throw new IllegalArgumentException(
                    "Runtime enforcement must match Run Mode and Repository Scope");
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
                    "Runtime writable repositories must match Run Mode and Scope");
        }
    }

    private static String requireStageIdentifier(String value) {
        String normalized = DomainText.require(
                value, "Stage Instance identifier", 128);
        if (!STAGE_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Stage Instance identifier is invalid");
        }
        return normalized;
    }

    private static <T> List<T> immutable(
            List<T> values, String name, boolean allowEmpty) {
        if (values == null || values.contains(null)
                || !allowEmpty && values.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must be complete");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
