package com.example.agentweb.domain.workbench;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Workbench 聚合内的阶段实体，集中守护人工状态、会话代际和单活动 Run。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchPhaseState {

    private final WorkbenchPhase phase;
    private WorkbenchPhaseStatus status;
    private final List<PhaseConversationReference> conversationHistory;
    private int conversationGeneration;
    private ActiveRunReference activeRunReference;
    private Instant lastActivityAt;
    private Instant completedAt;

    private WorkbenchPhaseState(WorkbenchPhase phase, WorkbenchPhaseStatus status,
                                List<PhaseConversationReference> conversationHistory,
                                int conversationGeneration,
                                ActiveRunReference activeRunReference,
                                Instant lastActivityAt, Instant completedAt) {
        if (phase == null || status == null) {
            throw new IllegalArgumentException("workbench phase and status are required");
        }
        if (conversationHistory == null || conversationHistory.contains(null)) {
            throw new IllegalArgumentException("conversation history must not contain null");
        }
        if (conversationGeneration < 0) {
            throw new IllegalArgumentException("conversation generation must not be negative");
        }
        this.phase = phase;
        this.status = status;
        this.conversationHistory = new ArrayList<PhaseConversationReference>(conversationHistory);
        this.conversationGeneration = conversationGeneration;
        this.activeRunReference = activeRunReference;
        this.lastActivityAt = lastActivityAt;
        this.completedAt = completedAt;
        validateState();
    }

    static WorkbenchPhaseState initial(WorkbenchPhase phase) {
        return new WorkbenchPhaseState(
                phase, WorkbenchPhaseStatus.NOT_STARTED,
                Collections.<PhaseConversationReference>emptyList(),
                0, null, null, null);
    }

    public static WorkbenchPhaseState restore(
            WorkbenchPhase phase, WorkbenchPhaseStatus status,
            List<PhaseConversationReference> conversationHistory,
            int conversationGeneration, ActiveRunReference activeRunReference,
            Instant lastActivityAt, Instant completedAt) {
        return new WorkbenchPhaseState(
                phase, status, conversationHistory, conversationGeneration,
                activeRunReference, lastActivityAt, completedAt);
    }

    public List<PhaseConversationReference> getConversationHistory() {
        return Collections.unmodifiableList(conversationHistory);
    }

    public PhaseConversationReference currentConversation() {
        if (conversationHistory.isEmpty()) {
            return null;
        }
        PhaseConversationReference current = conversationHistory.get(
                conversationHistory.size() - 1);
        return current.isActive() ? current : null;
    }

    boolean bindConversation(String conversationId, OwnerReference actor, Instant now) {
        PhaseConversationReference current = currentConversation();
        if (current != null) {
            if (current.getConversationId().equals(conversationId)) {
                return false;
            }
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.CONVERSATION_CONFLICT,
                    "phase already has a stable conversation: " + phase);
        }
        if (!conversationHistory.isEmpty()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.CONVERSATION_CONFLICT,
                    "phase conversation history has no active generation: " + phase);
        }
        conversationHistory.add(PhaseConversationReference.active(
                conversationId, 0, actor, now));
        conversationGeneration = 0;
        lastActivityAt = now;
        return true;
    }

    boolean restartConversation(String conversationId, OwnerReference actor, Instant now) {
        PhaseConversationReference current = requireRestartableConversation();
        if (current.getConversationId().equals(conversationId)) {
            return false;
        }
        conversationHistory.set(
                conversationHistory.size() - 1, current.retire(now));
        conversationGeneration++;
        conversationHistory.add(PhaseConversationReference.active(
                conversationId, conversationGeneration, actor, now));
        lastActivityAt = now;
        return true;
    }

    PhaseConversationReference requireRestartableConversation() {
        if (status != WorkbenchPhaseStatus.IN_PROGRESS || activeRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_RESTART_INVALID,
                    "phase conversation can restart only while idle and in progress: " + phase);
        }
        PhaseConversationReference current = currentConversation();
        if (current == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_RESTART_INVALID,
                    "phase has no current conversation to restart: " + phase);
        }
        return current;
    }

    ActiveRunReference prepareRun(String runId, RunMode runMode,
                                  ReviewModifyConfirmation reviewConfirmation,
                                  Instant now) {
        requireRunPreparationAvailable();
        PhaseRunPolicy.requireAllowed(phase, runMode, reviewConfirmation);
        activeRunReference = new ActiveRunReference(
                runId, phase, runMode, reviewConfirmation, now);
        if (status == WorkbenchPhaseStatus.NOT_STARTED) {
            status = WorkbenchPhaseStatus.IN_PROGRESS;
        }
        completedAt = null;
        lastActivityAt = now;
        return activeRunReference;
    }

    void requireRunPreparationAvailable() {
        if (status == WorkbenchPhaseStatus.HUMAN_COMPLETED) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                    "human-completed phase must be reopened before running: " + phase);
        }
        if (currentConversation() == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                    "phase conversation must be bound before preparing a run: " + phase);
        }
        if (activeRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_RUN_ACTIVE,
                    "phase already has an active run: " + phase);
        }
    }

    boolean finishRun(String runId, Instant now) {
        if (activeRunReference == null || !activeRunReference.matches(runId)) {
            return false;
        }
        activeRunReference = null;
        lastActivityAt = now;
        return true;
    }

    ActiveRunReference requireActiveRun(String runId) {
        if (activeRunReference == null || !activeRunReference.matches(runId)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return activeRunReference;
    }

    boolean complete(Instant now) {
        if (status == WorkbenchPhaseStatus.HUMAN_COMPLETED) {
            return false;
        }
        if (status != WorkbenchPhaseStatus.IN_PROGRESS || activeRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                    "phase can be completed only while idle and in progress: " + phase);
        }
        status = WorkbenchPhaseStatus.HUMAN_COMPLETED;
        completedAt = now;
        lastActivityAt = now;
        return true;
    }

    boolean reopen(Instant now) {
        if (status == WorkbenchPhaseStatus.IN_PROGRESS) {
            return false;
        }
        if (status != WorkbenchPhaseStatus.HUMAN_COMPLETED) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                    "only a human-completed phase can be reopened: " + phase);
        }
        status = WorkbenchPhaseStatus.IN_PROGRESS;
        completedAt = null;
        lastActivityAt = now;
        return true;
    }

    private void validateState() {
        if (conversationHistory.isEmpty()) {
            if (conversationGeneration != 0) {
                throw new IllegalArgumentException(
                        "empty conversation history must use generation zero");
            }
        } else {
            if (conversationHistory.size() != conversationGeneration + 1) {
                throw new IllegalArgumentException(
                        "conversation history must contain every generation");
            }
            for (int i = 0; i < conversationHistory.size(); i++) {
                PhaseConversationReference reference = conversationHistory.get(i);
                if (reference.getGeneration() != i) {
                    throw new IllegalArgumentException(
                            "conversation generations must be contiguous");
                }
                boolean latest = i == conversationHistory.size() - 1;
                if (latest != reference.isActive()) {
                    throw new IllegalArgumentException(
                            "only the latest conversation generation can be active");
                }
            }
        }
        if (status == WorkbenchPhaseStatus.HUMAN_COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("human-completed phase requires completedAt");
        }
        if (status != WorkbenchPhaseStatus.HUMAN_COMPLETED && completedAt != null) {
            throw new IllegalArgumentException(
                    "non-completed phase must not have completedAt");
        }
        if (activeRunReference != null && activeRunReference.getPhase() != phase) {
            throw new IllegalArgumentException("active run phase must match phase entity");
        }
        if (activeRunReference != null
                && (status != WorkbenchPhaseStatus.IN_PROGRESS
                || currentConversation() == null)) {
            throw new IllegalArgumentException(
                    "active run requires an in-progress phase with an active conversation");
        }
        if (activeRunReference != null
                && (lastActivityAt == null
                || lastActivityAt.isBefore(activeRunReference.getPreparedAt()))) {
            throw new IllegalArgumentException(
                    "active run requires phase activity at or after preparation");
        }
        if (status == WorkbenchPhaseStatus.IN_PROGRESS
                && currentConversation() == null) {
            throw new IllegalArgumentException(
                    "in-progress phase requires an active conversation");
        }
    }

    void requireConversationsCreatedBy(OwnerReference owner) {
        for (PhaseConversationReference reference : conversationHistory) {
            if (!reference.getCreatedBy().sameIdentityAs(owner)) {
                throw new IllegalArgumentException(
                        "phase conversation creator must match workbench owner");
            }
        }
    }
}
