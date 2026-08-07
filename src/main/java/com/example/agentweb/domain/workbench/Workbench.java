package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageRunReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic Stage Workbench 聚合根。
 *
 * <p>聚合集中守护 Owner、不可变 Stage Snapshot、人工状态、Stage 单 Run
 * 与 Workbench 全局写 Run 租约。</p>
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class Workbench {

    private final WorkbenchId id;
    private final OwnerReference owner;
    private final String title;
    private final String originalGoal;
    private final AgentType agentType;
    private final String environment;
    private final RepositoryScope repositoryScope;
    private final WorkspaceSnapshotReference creationSnapshotReference;
    private final Map<String, WorkbenchStageState> stages;
    private final boolean useWorktree;
    private final String worktreePath;
    private final String worktreeBranch;
    private WorkbenchStageRunReference activeWriteRunReference;
    private WorkbenchStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Workbench(
            WorkbenchId id, OwnerReference owner,
            String title, String originalGoal,
            AgentType agentType, String environment,
            RepositoryScope repositoryScope,
            WorkspaceSnapshotReference creationSnapshotReference,
            List<WorkbenchStageState> stages,
            boolean useWorktree, String worktreePath, String worktreeBranch,
            WorkbenchStageRunReference activeWriteRunReference,
            WorkbenchStatus status, Instant createdAt,
            Instant updatedAt, long version) {
        if (id == null || owner == null || agentType == null
                || repositoryScope == null
                || creationSnapshotReference == null || status == null) {
            throw new IllegalArgumentException(
                    "Workbench required values must not be null");
        }
        this.id = id;
        this.owner = owner;
        this.title = DomainText.require(title, "Workbench title", 512);
        this.originalGoal = DomainText.require(
                originalGoal, "Workbench original goal", 16000);
        this.agentType = agentType;
        this.environment = normalizeOptional(
                environment, 256, "Workbench environment");
        this.repositoryScope = repositoryScope;
        this.creationSnapshotReference = creationSnapshotReference;
        requireMatchingCreationSnapshot(
                repositoryScope, creationSnapshotReference);
        this.stages = indexStages(stages);
        requireRestoredConversationOwnership(this.stages, owner);
        this.useWorktree = useWorktree;
        this.worktreePath = useWorktree
                ? DomainText.require(worktreePath, "Workbench worktree path", 4096)
                : null;
        this.worktreeBranch = useWorktree
                ? DomainText.require(worktreeBranch, "Workbench worktree branch", 512)
                : null;
        if (!useWorktree && (worktreePath != null || worktreeBranch != null)) {
            throw new IllegalArgumentException(
                    "Workbench worktree path and branch must be null when worktree is not used");
        }
        this.activeWriteRunReference = activeWriteRunReference;
        this.status = status;
        this.createdAt = DomainText.requireTime(
                createdAt, "Workbench created at");
        this.updatedAt = DomainText.requireTime(
                updatedAt, "Workbench updated at");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Workbench updated time must not be before created time");
        }
        if (version < 0L) {
            throw new IllegalArgumentException(
                    "Workbench version must not be negative");
        }
        this.version = version;
        validateRestoredWriteLease();
    }

    public static Workbench create(
            WorkbenchId id, OwnerReference owner,
            String title, String originalGoal,
            AgentType agentType, String environment,
            RepositoryScope repositoryScope,
            WorkspaceSnapshotReference creationSnapshotReference,
            List<WorkbenchStageState> stages, Instant now) {
        return new Workbench(
                id, owner, title, originalGoal, agentType, environment,
                repositoryScope, creationSnapshotReference, stages,
                false, null, null, null,
                WorkbenchStatus.ACTIVE, now, now, 0L);
    }

    public static Workbench createWithWorktree(
            WorkbenchId id, OwnerReference owner,
            String title, String originalGoal,
            AgentType agentType, String environment,
            RepositoryScope repositoryScope,
            WorkspaceSnapshotReference creationSnapshotReference,
            List<WorkbenchStageState> stages, Instant now,
            String worktreePath, String worktreeBranch) {
        return new Workbench(
                id, owner, title, originalGoal, agentType, environment,
                repositoryScope, creationSnapshotReference, stages,
                true, worktreePath, worktreeBranch, null,
                WorkbenchStatus.ACTIVE, now, now, 0L);
    }

    public static Workbench restore(
            WorkbenchId id, OwnerReference owner,
            String title, String originalGoal,
            AgentType agentType, String environment,
            RepositoryScope repositoryScope,
            WorkspaceSnapshotReference creationSnapshotReference,
            List<WorkbenchStageState> stages,
            boolean useWorktree, String worktreePath, String worktreeBranch,
            WorkbenchStageRunReference activeWriteRunReference,
            WorkbenchStatus status, Instant createdAt,
            Instant updatedAt, long version) {
        return new Workbench(
                id, owner, title, originalGoal, agentType, environment,
                repositoryScope, creationSnapshotReference, stages,
                useWorktree, worktreePath, worktreeBranch,
                activeWriteRunReference, status,
                createdAt, updatedAt, version);
    }

    public List<WorkbenchStageState> getStages() {
        return Collections.unmodifiableList(
                new ArrayList<WorkbenchStageState>(stages.values()));
    }

    public WorkbenchStageState stage(String stageInstanceIdentifier) {
        String identifier = DomainText.require(
                stageInstanceIdentifier, "Stage Instance identifier", 128);
        WorkbenchStageState state = stages.get(identifier);
        if (state == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_NOT_FOUND,
                    "Workbench Stage does not exist: " + identifier);
        }
        return state;
    }

    public void requireOperableBy(OwnerReference actor) {
        requireOwnedBy(actor);
        requireActive();
    }

    public void requireOwnedBy(OwnerReference actor) {
        if (!owner.sameIdentityAs(actor)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OWNER_REQUIRED,
                    "Only the Workbench Owner can perform this operation");
        }
    }

    public boolean completeStage(
            String stageInstanceIdentifier, OwnerReference actor,
            long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        Instant activityTime = requireActivityTime(now);
        boolean changed = stage(stageInstanceIdentifier).complete(activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public boolean reopenStage(
            String stageInstanceIdentifier, OwnerReference actor,
            long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        Instant activityTime = requireActivityTime(now);
        boolean changed = stage(stageInstanceIdentifier).reopen(activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public WorkbenchStageRunReference prepareStageRun(
            String stageInstanceIdentifier, String runIdentifier,
            RunMode runMode, OwnerReference actor,
            long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        Instant activityTime = requireActivityTime(now);
        WorkbenchStageState stageState = stage(stageInstanceIdentifier);
        stageState.requireRunPreparationAvailable(runMode);
        if (runMode.modifiesWorkspace()
                && activeWriteRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.WRITE_RUN_ACTIVE,
                    "Workbench already has an active Stage modify Run");
        }
        WorkbenchStageRunReference prepared = stageState.prepareRun(
                runIdentifier, runMode, activityTime);
        if (runMode.modifiesWorkspace()) {
            activeWriteRunReference = prepared;
        }
        recordMutation(activityTime);
        return prepared;
    }

    public boolean finishStageRun(
            String stageInstanceIdentifier, String runIdentifier,
            Instant now) {
        Instant activityTime = requireActivityTime(now);
        WorkbenchStageState stageState = stage(stageInstanceIdentifier);
        WorkbenchStageRunReference activeRun =
                stageState.getActiveRunReference();
        boolean changed = stageState.finishRun(runIdentifier, activityTime);
        if (!changed) {
            return false;
        }
        if (activeWriteRunReference != null
                && activeWriteRunReference.equals(activeRun)) {
            activeWriteRunReference = null;
        }
        recordMutation(activityTime);
        return true;
    }

    void finishRequiredStageRun(
            String stageInstanceIdentifier, String runIdentifier,
            Instant now) {
        WorkbenchStageState stageState = stage(stageInstanceIdentifier);
        stageState.requireActiveRun(runIdentifier);
        if (!finishStageRun(
                stageInstanceIdentifier, runIdentifier, now)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    public boolean bindStageConversation(
            String stageInstanceIdentifier, String conversationId,
            OwnerReference actor, long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        return bindStageConversation(
                stageInstanceIdentifier, conversationId, actor, now);
    }

    public boolean bindStageConversation(
            String stageInstanceIdentifier, String conversationId,
            OwnerReference actor, Instant now) {
        requireOperableBy(actor);
        Instant activityTime = requireActivityTime(now);
        boolean changed = stage(stageInstanceIdentifier).bindConversation(
                conversationId, actor, activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public boolean restartStageConversation(
            String stageInstanceIdentifier, String conversationId,
            OwnerReference actor, long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        return restartStageConversation(
                stageInstanceIdentifier, conversationId, actor, now);
    }

    public boolean restartStageConversation(
            String stageInstanceIdentifier, String conversationId,
            OwnerReference actor, Instant now) {
        requireOperableBy(actor);
        Instant activityTime = requireActivityTime(now);
        boolean changed = stage(stageInstanceIdentifier).restartConversation(
                conversationId, actor, activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public WorkbenchStageConversationProvisioning planStageConversationEnsure(
            String stageInstanceIdentifier, OwnerReference actor,
            long expectedVersion) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        WorkbenchStageState state = stage(stageInstanceIdentifier);
        return WorkbenchStageConversationProvisioning.plan(
                this, state, state.currentConversation());
    }

    public WorkbenchStageRunPreparationPlan planStageRunPreparation(
            String stageInstanceIdentifier, RunMode runMode,
            OwnerReference actor, long expectedVersion) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        WorkbenchStageState state = stage(stageInstanceIdentifier);
        state.requireRunPreparationAvailable(runMode);
        if (runMode.modifiesWorkspace()
                && activeWriteRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.WRITE_RUN_ACTIVE,
                    "Workbench already has an active Stage modify Run");
        }
        WorkbenchStageConversationProvisioning conversation =
                WorkbenchStageConversationProvisioning.plan(
                        this, state, state.currentConversation());
        return WorkbenchStageRunPreparationPlan.plan(
                this, state, runMode, conversation);
    }

    public WorkbenchStageConversationProvisioning planStageConversationRestart(
            String stageInstanceIdentifier, OwnerReference actor,
            long expectedVersion) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        WorkbenchStageState state = stage(stageInstanceIdentifier);
        return WorkbenchStageConversationProvisioning.plan(
                this, state, state.requireRestartableConversation());
    }

    public WorkbenchStageUploadedAttachmentBinding
            planStageUploadedAttachment(
                    String stageInstanceIdentifier,
                    int conversationGeneration,
                    OwnerReference actor) {
        requireOperableBy(actor);
        if (conversationGeneration < 0) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_INVALID,
                    "Stage uploaded attachment generation is invalid");
        }
        WorkbenchStageConversationReference current =
                stage(stageInstanceIdentifier).currentConversation();
        if (current == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.CONVERSATION_CONFLICT,
                    "Stage conversation must exist before uploading an attachment");
        }
        if (current.getGeneration() != conversationGeneration) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "Stage uploaded attachment generation is stale");
        }
        return new WorkbenchStageUploadedAttachmentBinding(
                owner, id, stageInstanceIdentifier,
                current.getConversationId(), current.getGeneration());
    }

    public WorkbenchStageConversationProvisioning
            bindStageConversationAndDescribe(
                    String stageInstanceIdentifier, String conversationId,
                    OwnerReference actor, Instant now) {
        bindStageConversation(
                stageInstanceIdentifier, conversationId, actor, now);
        return describeCurrentStageConversation(stageInstanceIdentifier);
    }

    public WorkbenchStageConversationProvisioning
            restartStageConversationAndDescribe(
                    String stageInstanceIdentifier, String conversationId,
                    OwnerReference actor, Instant now) {
        restartStageConversation(
                stageInstanceIdentifier, conversationId, actor, now);
        return describeCurrentStageConversation(stageInstanceIdentifier);
    }

    public boolean archive(OwnerReference actor, Instant now) {
        requireOwnedBy(actor);
        if (status == WorkbenchStatus.ARCHIVED) {
            return false;
        }
        if (activeWriteRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.WRITE_RUN_ACTIVE,
                    "Workbench cannot be archived while a modify Run is active");
        }
        Instant archiveTime = requireActivityTime(now);
        status = WorkbenchStatus.ARCHIVED;
        recordMutation(archiveTime);
        return true;
    }

    public boolean archive(
            OwnerReference actor, long expectedVersion, Instant now) {
        requireOwnedBy(actor);
        requireExpectedVersion(expectedVersion);
        return archive(actor, now);
    }

    private WorkbenchStageConversationProvisioning
            describeCurrentStageConversation(String stageInstanceIdentifier) {
        WorkbenchStageState state = stage(stageInstanceIdentifier);
        if (state.currentConversation() == null) {
            throw new IllegalStateException(
                    "Stage conversation mutation produced no current Session");
        }
        return WorkbenchStageConversationProvisioning.plan(
                this, state, state.currentConversation());
    }

    private void requireActive() {
        if (status == WorkbenchStatus.ARCHIVED) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ARCHIVED,
                    "Archived Workbench is read-only");
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "Expected Workbench version must not be negative");
        }
        if (version != expectedVersion) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "Stale Workbench version");
        }
    }

    private Instant requireActivityTime(Instant now) {
        Instant value = DomainText.requireTime(now, "Workbench activity time");
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "Workbench activity time must not be before updated time");
        }
        return value;
    }

    private void recordMutation(Instant now) {
        updatedAt = now;
        version++;
    }

    private void validateRestoredWriteLease() {
        WorkbenchStageRunReference discoveredWriteRun = null;
        for (WorkbenchStageState state : stages.values()) {
            WorkbenchStageRunReference activeRun =
                    state.getActiveRunReference();
            if (activeRun != null
                    && activeRun.getRunMode().modifiesWorkspace()) {
                if (discoveredWriteRun != null) {
                    throw new IllegalArgumentException(
                            "Workbench cannot restore more than one Stage modify Run");
                }
                discoveredWriteRun = activeRun;
            }
        }
        if (discoveredWriteRun == null && activeWriteRunReference != null
                || discoveredWriteRun != null
                && !discoveredWriteRun.equals(activeWriteRunReference)) {
            throw new IllegalArgumentException(
                    "Workbench write lease must match the active Stage modify Run");
        }
    }

    private static Map<String, WorkbenchStageState> indexStages(
            List<WorkbenchStageState> stageStates) {
        if (stageStates == null || stageStates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Workbench Stage states must not be empty");
        }
        if (stageStates.contains(null)) {
            throw new IllegalArgumentException(
                    "Workbench Stage states must not contain null");
        }
        List<WorkbenchStageState> sorted =
                new ArrayList<WorkbenchStageState>(stageStates);
        sorted.sort(java.util.Comparator.comparingInt(
                stage -> stage.getSnapshot().getSequenceNumber()));
        Map<String, WorkbenchStageState> byInstance =
                new LinkedHashMap<String, WorkbenchStageState>();
        Set<String> definitions = new HashSet<String>();
        Set<Integer> sequences = new HashSet<Integer>();
        for (WorkbenchStageState state : sorted) {
            if (byInstance.put(
                    state.getStageInstanceIdentifier(), state) != null
                    || !definitions.add(
                    state.getSnapshot().getDefinitionIdentifier())
                    || !sequences.add(
                    state.getSnapshot().getSequenceNumber())) {
                throw new IllegalArgumentException(
                        "Workbench Stage instances, definitions and sequences "
                                + "must be unique");
            }
        }
        return byInstance;
    }

    private static void requireMatchingCreationSnapshot(
            RepositoryScope repositoryScope,
            WorkspaceSnapshotReference creationSnapshotReference) {
        if (!repositoryScope.matchesSnapshotTopology(
                creationSnapshotReference)
                || repositoryScope.repositoryCount()
                != creationSnapshotReference.getRepositoryCount()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.REPOSITORY_SCOPE_INVALID,
                    "Creation Snapshot must match the immutable Repository Scope");
        }
    }

    private static void requireRestoredConversationOwnership(
            Map<String, WorkbenchStageState> stageStates,
            OwnerReference owner) {
        for (WorkbenchStageState state : stageStates.values()) {
            state.requireConversationsCreatedBy(owner);
        }
    }

    private static String normalizeOptional(
            String value, int maxLength, String name) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return DomainText.require(value, name, maxLength);
    }
}
