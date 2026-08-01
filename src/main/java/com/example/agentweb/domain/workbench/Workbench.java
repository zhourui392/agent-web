package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 本地开发 Workbench 聚合根。
 *
 * <p>聚合集中守护固定四阶段、Owner、人工状态、阶段单 Run 与全局写 Run 租约；
 * 不承载 Harness Gate/PASS 语义。</p>
 *
 * @author alex
 * @since 2026-08-01
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
    private final Map<WorkbenchPhase, WorkbenchPhaseState> phases;
    private ActiveRunReference activeWriteRunReference;
    private WorkbenchStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Workbench(WorkbenchId id, OwnerReference owner, String title, String originalGoal,
                      AgentType agentType, String environment,
                      RepositoryScope repositoryScope,
                      WorkspaceSnapshotReference creationSnapshotReference,
                      List<WorkbenchPhaseState> phases,
                      ActiveRunReference activeWriteRunReference,
                      WorkbenchStatus status, Instant createdAt, Instant updatedAt,
                      long version) {
        if (id == null || owner == null || agentType == null || repositoryScope == null
                || creationSnapshotReference == null || status == null) {
            throw new IllegalArgumentException("workbench required values must not be null");
        }
        this.id = id;
        this.owner = owner;
        this.title = DomainText.require(title, "workbench title", 512);
        this.originalGoal = DomainText.require(originalGoal, "workbench original goal", 16000);
        this.agentType = agentType;
        this.environment = normalizeOptional(environment, 256, "workbench environment");
        this.repositoryScope = repositoryScope;
        this.creationSnapshotReference = creationSnapshotReference;
        if (!repositoryScope.matchesSnapshotTopology(creationSnapshotReference)
                || repositoryScope.repositoryCount()
                != creationSnapshotReference.getRepositoryCount()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.REPOSITORY_SCOPE_INVALID,
                    "creation snapshot must match the immutable repository scope");
        }
        this.phases = indexExactlyFourPhases(phases);
        requireRestoredConversationOwnership(this.phases, owner);
        this.activeWriteRunReference = activeWriteRunReference;
        this.status = status;
        this.createdAt = DomainText.requireTime(createdAt, "workbench created at");
        this.updatedAt = DomainText.requireTime(updatedAt, "workbench updated at");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "workbench updated time must not be before created time");
        }
        if (version < 0L) {
            throw new IllegalArgumentException("workbench version must not be negative");
        }
        this.version = version;
        validateRestoredLease();
    }

    public static Workbench create(WorkbenchId id, OwnerReference owner,
                                   String title, String originalGoal,
                                   AgentType agentType, String environment,
                                   RepositoryScope repositoryScope,
                                   WorkspaceSnapshotReference creationSnapshotReference,
                                   Instant now) {
        List<WorkbenchPhaseState> phases = new ArrayList<WorkbenchPhaseState>();
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            phases.add(WorkbenchPhaseState.initial(phase));
        }
        return new Workbench(
                id, owner, title, originalGoal, agentType, environment,
                repositoryScope, creationSnapshotReference, phases, null,
                WorkbenchStatus.ACTIVE, now, now, 0L);
    }

    public static Workbench restore(WorkbenchId id, OwnerReference owner,
                                    String title, String originalGoal,
                                    AgentType agentType, String environment,
                                    RepositoryScope repositoryScope,
                                    WorkspaceSnapshotReference creationSnapshotReference,
                                    List<WorkbenchPhaseState> phases,
                                    ActiveRunReference activeWriteRunReference,
                                    WorkbenchStatus status, Instant createdAt,
                                    Instant updatedAt, long version) {
        return new Workbench(
                id, owner, title, originalGoal, agentType, environment,
                repositoryScope, creationSnapshotReference, phases,
                activeWriteRunReference, status, createdAt, updatedAt, version);
    }

    public List<WorkbenchPhaseState> getPhases() {
        return Collections.unmodifiableList(new ArrayList<WorkbenchPhaseState>(phases.values()));
    }

    public WorkbenchPhaseState phase(WorkbenchPhase phase) {
        if (phase == null) {
            throw new IllegalArgumentException("workbench phase must not be null");
        }
        WorkbenchPhaseState state = phases.get(phase);
        if (state == null) {
            throw new IllegalStateException("workbench phase is missing: " + phase);
        }
        return state;
    }

    public void requireOperableBy(OwnerReference actor) {
        requireOwnedBy(actor);
        requireActive();
    }

    public void requireOwnedBy(OwnerReference actor) {
        requireOwner(actor);
    }

    public boolean bindConversation(WorkbenchPhase phase, String conversationId,
                                    OwnerReference actor, Instant now) {
        requireOperableBy(actor);
        Instant activityTime = requireActivityTime(now);
        boolean changed = phase(phase).bindConversation(conversationId, actor, activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public boolean restartConversation(WorkbenchPhase phase, String newConversationId,
                                       OwnerReference actor, Instant now) {
        requireOperableBy(actor);
        Instant activityTime = requireActivityTime(now);
        boolean changed = phase(phase).restartConversation(
                newConversationId, actor, activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public PhaseConversationProvisioning planConversationEnsure(
            WorkbenchPhase phase, OwnerReference actor, long expectedVersion) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        WorkbenchPhaseState state = phase(phase);
        return PhaseConversationProvisioning.plan(this, phase, state.currentConversation());
    }

    /**
     * 在任何外部准备动作前冻结本次 Run 的领域要求。
     */
    public WorkbenchRunPreparationPlan planRunPreparation(
            WorkbenchPhase phase, RunMode runMode,
            Long handoffSourceVersion, String reviewConfirmationId,
            OwnerReference actor, long expectedVersion) {
        PhaseConversationProvisioning conversation = planConversationEnsure(
                phase, actor, expectedVersion);
        WorkbenchRunPreparationPlan plan = WorkbenchRunPreparationPlan.plan(
                this, phase, runMode, handoffSourceVersion,
                reviewConfirmationId, conversation);
        phase(phase).requireRunPreparationAvailable();
        if (runMode.modifiesWorkspace() && activeWriteRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.WRITE_RUN_ACTIVE,
                    "workbench already has an active modify run");
        }
        return plan;
    }

    public PhaseConversationProvisioning planConversationRestart(
            WorkbenchPhase phase, OwnerReference actor, long expectedVersion) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        WorkbenchPhaseState state = phase(phase);
        return PhaseConversationProvisioning.plan(
                this, phase, state.requireRestartableConversation());
    }

    public PhaseConversationProvisioning bindConversationAndDescribe(
            WorkbenchPhase phase, String conversationId,
            OwnerReference actor, Instant now) {
        bindConversation(phase, conversationId, actor, now);
        return describeCurrentConversation(phase);
    }

    public PhaseConversationProvisioning restartConversationAndDescribe(
            WorkbenchPhase phase, String conversationId,
            OwnerReference actor, Instant now) {
        restartConversation(phase, conversationId, actor, now);
        return describeCurrentConversation(phase);
    }

    public ActiveRunReference prepareRun(WorkbenchPhase phase, String runId,
                                         RunMode runMode, OwnerReference actor, Instant now) {
        return prepareRun(phase, runId, runMode, null, actor, now);
    }

    public ActiveRunReference prepareRun(
            WorkbenchPhase phase, String runId, RunMode runMode,
            OwnerReference actor, long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        return prepareRun(phase, runId, runMode, null, actor, now);
    }

    public ActiveRunReference prepareRun(
            WorkbenchPhase phase, String runId, RunMode runMode,
            ReviewModifyConfirmation reviewConfirmation,
            OwnerReference actor, long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        return prepareRun(
                phase, runId, runMode, reviewConfirmation, actor, now);
    }

    public ActiveRunReference prepareReviewRefactorRun(
            String runId, RunMode runMode, ReviewModifyConfirmation reviewConfirmation,
            OwnerReference actor, Instant now) {
        return prepareRun(
                WorkbenchPhase.REVIEW_REFACTOR, runId, runMode,
                reviewConfirmation, actor, now);
    }

    public ActiveRunReference prepareReviewRefactorRun(
            String runId, RunMode runMode,
            ReviewModifyConfirmation reviewConfirmation,
            OwnerReference actor, long expectedVersion, Instant now) {
        requireOperableBy(actor);
        requireExpectedVersion(expectedVersion);
        return prepareRun(
                WorkbenchPhase.REVIEW_REFACTOR, runId, runMode,
                reviewConfirmation, actor, now);
    }

    private ActiveRunReference prepareRun(
            WorkbenchPhase phase, String runId, RunMode runMode,
            ReviewModifyConfirmation reviewConfirmation,
            OwnerReference actor, Instant now) {
        requireOperableBy(actor);
        Instant activityTime = requireActivityTime(now);
        if (runMode == null) {
            throw new IllegalArgumentException("workbench run mode must not be null");
        }
        if (runMode.modifiesWorkspace() && activeWriteRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.WRITE_RUN_ACTIVE,
                    "workbench already has an active modify run");
        }
        if (reviewConfirmation != null
                && !reviewConfirmation.isValidFor(id, actor, activityTime)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "review confirmation must bind the current workbench, owner and opinion");
        }
        ActiveRunReference prepared = phase(phase).prepareRun(
                runId, runMode, reviewConfirmation, activityTime);
        if (runMode.modifiesWorkspace()) {
            activeWriteRunReference = prepared;
        }
        recordMutation(activityTime);
        return prepared;
    }

    public boolean finishRun(WorkbenchPhase phase, String runId, Instant now) {
        Instant activityTime = requireActivityTime(now);
        boolean changed = phase(phase).finishRun(runId, activityTime);
        if (!changed) {
            return false;
        }
        if (activeWriteRunReference != null && activeWriteRunReference.matches(runId)) {
            activeWriteRunReference = null;
        }
        recordMutation(activityTime);
        return true;
    }

    public WorkbenchPhaseStatus phaseStatus(WorkbenchPhase phase) {
        return phase(phase).getStatus();
    }

    /**
     * 首次终态严格释放：活动 Phase 引用和 MODIFY 写租约必须与候选 Run 精确一致。
     */
    void finishRequiredRun(WorkbenchPhase phase, String runId, Instant now) {
        Instant activityTime = requireActivityTime(now);
        WorkbenchPhaseState phaseState = phase(phase);
        ActiveRunReference activeRun = phaseState.requireActiveRun(runId);
        requireConsistentTerminalLease(activeRun);
        phaseState.finishRun(runId, activityTime);
        if (activeWriteRunReference != null
                && activeWriteRunReference.equals(activeRun)) {
            activeWriteRunReference = null;
        }
        recordMutation(activityTime);
    }

    public boolean completePhase(WorkbenchPhase phase, OwnerReference actor, Instant now) {
        requireOperableBy(actor);
        Instant activityTime = requireActivityTime(now);
        boolean changed = phase(phase).complete(activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public boolean reopenPhase(WorkbenchPhase phase, OwnerReference actor, Instant now) {
        requireOperableBy(actor);
        Instant activityTime = requireActivityTime(now);
        boolean changed = phase(phase).reopen(activityTime);
        if (changed) {
            recordMutation(activityTime);
        }
        return changed;
    }

    public boolean archive(OwnerReference actor, Instant now) {
        requireOwner(actor);
        if (status == WorkbenchStatus.ARCHIVED) {
            return false;
        }
        if (activeWriteRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.WRITE_RUN_ACTIVE,
                    "workbench cannot be archived while a modify run is active");
        }
        Instant archiveTime = requireActivityTime(now);
        status = WorkbenchStatus.ARCHIVED;
        recordMutation(archiveTime);
        return true;
    }

    private void requireOwner(OwnerReference actor) {
        if (!owner.sameIdentityAs(actor)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OWNER_REQUIRED,
                    "only the workbench owner can perform this operation");
        }
    }

    private void requireActive() {
        if (status == WorkbenchStatus.ARCHIVED) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ARCHIVED,
                    "archived workbench is read-only");
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expected workbench version must not be negative");
        }
        if (version != expectedVersion) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "stale workbench version");
        }
    }

    private PhaseConversationProvisioning describeCurrentConversation(
            WorkbenchPhase phase) {
        PhaseConversationReference current = phase(phase).currentConversation();
        if (current == null) {
            throw new IllegalStateException("phase conversation mutation produced no current session");
        }
        return PhaseConversationProvisioning.plan(this, phase, current);
    }

    private Instant requireActivityTime(Instant now) {
        Instant value = DomainText.requireTime(now, "workbench activity time");
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "workbench activity time must not be before updated time");
        }
        return value;
    }

    private void recordMutation(Instant now) {
        updatedAt = now;
        version++;
    }

    private static Map<WorkbenchPhase, WorkbenchPhaseState> indexExactlyFourPhases(
            List<WorkbenchPhaseState> phaseStates) {
        if (phaseStates == null || phaseStates.size() != WorkbenchPhase.values().length
                || phaseStates.contains(null)) {
            throw new IllegalArgumentException(
                    "workbench must contain exactly the four fixed phases");
        }
        Map<WorkbenchPhase, WorkbenchPhaseState> byPhase =
                new EnumMap<WorkbenchPhase, WorkbenchPhaseState>(WorkbenchPhase.class);
        for (WorkbenchPhaseState state : phaseStates) {
            if (byPhase.put(state.getPhase(), state) != null) {
                throw new IllegalArgumentException(
                        "workbench phases must not contain duplicates: " + state.getPhase());
            }
        }
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            if (!byPhase.containsKey(phase)) {
                throw new IllegalArgumentException("workbench phase is missing: " + phase);
            }
        }
        return byPhase;
    }

    private void validateRestoredLease() {
        ActiveRunReference discoveredWriteRun = null;
        for (WorkbenchPhaseState state : phases.values()) {
            ActiveRunReference activeRun = state.getActiveRunReference();
            if (activeRun != null && activeRun.getRunMode().modifiesWorkspace()) {
                if (discoveredWriteRun != null) {
                    throw new IllegalArgumentException(
                            "workbench cannot restore more than one modify run");
                }
                discoveredWriteRun = activeRun;
            }
        }
        if (discoveredWriteRun == null && activeWriteRunReference != null
                || discoveredWriteRun != null
                && !discoveredWriteRun.equals(activeWriteRunReference)) {
            throw new IllegalArgumentException(
                    "workbench write lease must match the active phase modify run");
        }
    }

    private void requireConsistentTerminalLease(ActiveRunReference activeRun) {
        boolean modifyLeaseMissing = activeRun.getRunMode().modifiesWorkspace()
                && !activeRun.equals(activeWriteRunReference);
        boolean readOnlyRunOwnsWriteLease = !activeRun.getRunMode().modifiesWorkspace()
                && activeWriteRunReference != null
                && activeWriteRunReference.matches(activeRun.getRunId());
        if (modifyLeaseMissing || readOnlyRunOwnsWriteLease) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    private static void requireRestoredConversationOwnership(
            Map<WorkbenchPhase, WorkbenchPhaseState> phaseStates,
            OwnerReference owner) {
        for (WorkbenchPhaseState state : phaseStates.values()) {
            state.requireConversationsCreatedBy(owner);
        }
    }

    private static String normalizeOptional(String value, int maxLength, String name) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return DomainText.require(value, name, maxLength);
    }
}
