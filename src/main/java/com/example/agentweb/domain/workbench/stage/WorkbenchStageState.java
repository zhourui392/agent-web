package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 某个 Workbench 内独立 Stage Instance 的运行状态。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageState {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final String stageInstanceIdentifier;
    private final WorkbenchStageSnapshot snapshot;
    private WorkbenchStageStatus status;
    private final List<WorkbenchStageConversationReference> conversationHistory;
    private int conversationGeneration;
    private WorkbenchStageRunReference activeRunReference;
    private Instant lastActivityAt;
    private Instant completedAt;

    private WorkbenchStageState(
            String stageInstanceIdentifier, WorkbenchStageSnapshot snapshot,
            WorkbenchStageStatus status,
            List<WorkbenchStageConversationReference> conversationHistory,
            int conversationGeneration,
            WorkbenchStageRunReference activeRunReference,
            Instant lastActivityAt, Instant completedAt) {
        String identifier = DomainText.require(
                stageInstanceIdentifier, "Stage Instance identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Stage Instance identifier format is invalid");
        }
        if (snapshot == null || status == null || conversationHistory == null
                || containsNull(conversationHistory)
                || conversationGeneration < 0) {
            throw new IllegalArgumentException(
                    "Stage Instance facts are invalid");
        }
        if (status == WorkbenchStageStatus.HUMAN_COMPLETED
                != (completedAt != null)) {
            throw new IllegalArgumentException(
                    "Stage Instance completion time must match its status");
        }
        this.stageInstanceIdentifier = identifier;
        this.snapshot = snapshot;
        this.status = status;
        this.conversationHistory =
                new ArrayList<WorkbenchStageConversationReference>(
                        conversationHistory);
        this.conversationGeneration = conversationGeneration;
        this.activeRunReference = activeRunReference;
        this.lastActivityAt = lastActivityAt;
        this.completedAt = completedAt;
        validateState();
    }

    public static WorkbenchStageState initial(
            String stageInstanceIdentifier, WorkbenchStageSnapshot snapshot) {
        return new WorkbenchStageState(
                stageInstanceIdentifier, snapshot,
                WorkbenchStageStatus.NOT_STARTED,
                Collections.emptyList(), 0,
                null, null, null);
    }

    public static WorkbenchStageState restore(
            String stageInstanceIdentifier, WorkbenchStageSnapshot snapshot,
            WorkbenchStageStatus status, int conversationGeneration,
            String activeRunIdentifier, RunMode activeRunMode,
            Instant activeRunPreparedAt,
            Instant lastActivityAt, Instant completedAt) {
        return new WorkbenchStageState(
                stageInstanceIdentifier, snapshot, status,
                Collections.emptyList(), conversationGeneration,
                restoreRunReference(activeRunIdentifier, activeRunMode,
                        activeRunPreparedAt),
                lastActivityAt, completedAt);
    }

    public static WorkbenchStageState restore(
            String stageInstanceIdentifier, WorkbenchStageSnapshot snapshot,
            WorkbenchStageStatus status,
            List<WorkbenchStageConversationReference> conversationHistory,
            int conversationGeneration, String activeRunIdentifier,
            RunMode activeRunMode, Instant activeRunPreparedAt,
            Instant lastActivityAt,
            Instant completedAt) {
        return new WorkbenchStageState(
                stageInstanceIdentifier, snapshot, status, conversationHistory,
                conversationGeneration, restoreRunReference(
                        activeRunIdentifier, activeRunMode,
                        activeRunPreparedAt), lastActivityAt, completedAt);
    }

    public String getActiveRunIdentifier() {
        return activeRunReference == null
                ? null : activeRunReference.getRunIdentifier();
    }

    public RunMode getActiveRunMode() {
        return activeRunReference == null
                ? null : activeRunReference.getRunMode();
    }

    public Instant getActiveRunPreparedAt() {
        return activeRunReference == null
                ? null : activeRunReference.getPreparedAt();
    }

    public List<WorkbenchStageConversationReference> getConversationHistory() {
        return Collections.unmodifiableList(conversationHistory);
    }

    public WorkbenchStageConversationReference currentConversation() {
        if (conversationHistory.isEmpty()) {
            return null;
        }
        WorkbenchStageConversationReference current = conversationHistory.get(
                conversationHistory.size() - 1);
        return current.isActive() ? current : null;
    }

    public boolean bindConversation(
            String conversationId, OwnerReference actor, Instant now) {
        WorkbenchStageConversationReference current = currentConversation();
        if (current != null) {
            if (current.getConversationId().equals(conversationId)) {
                return false;
            }
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.CONVERSATION_CONFLICT,
                    "Stage already has a stable conversation: "
                            + stageInstanceIdentifier);
        }
        if (!conversationHistory.isEmpty()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.CONVERSATION_CONFLICT,
                    "Stage conversation history has no active generation: "
                            + stageInstanceIdentifier);
        }
        conversationHistory.add(WorkbenchStageConversationReference.active(
                conversationId, 0, actor, now));
        conversationGeneration = 0;
        lastActivityAt = now;
        return true;
    }

    public boolean restartConversation(
            String conversationId, OwnerReference actor, Instant now) {
        WorkbenchStageConversationReference current =
                requireRestartableConversation();
        if (current.getConversationId().equals(conversationId)) {
            return false;
        }
        conversationHistory.set(
                conversationHistory.size() - 1, current.retire(now));
        conversationGeneration++;
        conversationHistory.add(WorkbenchStageConversationReference.active(
                conversationId, conversationGeneration, actor, now));
        lastActivityAt = now;
        return true;
    }

    public WorkbenchStageConversationReference requireRestartableConversation() {
        if (status != WorkbenchStageStatus.IN_PROGRESS
                || activeRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_RESTART_INVALID,
                    "Stage conversation can restart only while idle and in progress: "
                            + stageInstanceIdentifier);
        }
        WorkbenchStageConversationReference current = currentConversation();
        if (current == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_RESTART_INVALID,
                    "Stage has no current conversation to restart: "
                            + stageInstanceIdentifier);
        }
        return current;
    }

    public WorkbenchStageRunReference prepareRun(
            String runIdentifier, RunMode runMode, Instant now) {
        requireRunPreparationAvailable(runMode);
        WorkbenchStageRunReference prepared =
                WorkbenchStageRunReference.prepare(
                        runIdentifier, runMode, now);
        activeRunReference = prepared;
        if (status == WorkbenchStageStatus.NOT_STARTED) {
            status = WorkbenchStageStatus.IN_PROGRESS;
        }
        completedAt = null;
        lastActivityAt = now;
        return prepared;
    }

    public void requireRunPreparationAvailable(RunMode runMode) {
        snapshot.requireRunModeAllowed(runMode);
        if (status == WorkbenchStageStatus.HUMAN_COMPLETED) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_TRANSITION_INVALID,
                    "Human-completed Stage must be reopened before running: "
                            + stageInstanceIdentifier);
        }
        if (currentConversation() == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_TRANSITION_INVALID,
                    "Stage Conversation must exist before preparing a Run: "
                            + stageInstanceIdentifier);
        }
        if (activeRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_RUN_ACTIVE,
                    "Stage already has an active Run: "
                            + stageInstanceIdentifier);
        }
    }

    public boolean finishRun(String runIdentifier, Instant now) {
        if (activeRunReference == null
                || !activeRunReference.matches(runIdentifier)) {
            return false;
        }
        activeRunReference = null;
        lastActivityAt = DomainText.requireTime(now, "Stage Run finish time");
        return true;
    }

    public WorkbenchStageRunReference requireActiveRun(
            String runIdentifier) {
        if (activeRunReference == null
                || !activeRunReference.matches(runIdentifier)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return activeRunReference;
    }

    public boolean complete(Instant now) {
        Instant completedTime = DomainText.requireTime(now, "Stage completion time");
        if (status == WorkbenchStageStatus.HUMAN_COMPLETED) {
            return false;
        }
        if (activeRunReference != null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_TRANSITION_INVALID,
                    "Stage can be completed only while idle");
        }
        status = WorkbenchStageStatus.HUMAN_COMPLETED;
        completedAt = completedTime;
        lastActivityAt = completedTime;
        return true;
    }

    public boolean reopen(Instant now) {
        Instant reopenedTime = DomainText.requireTime(now, "Stage reopen time");
        if (status == WorkbenchStageStatus.IN_PROGRESS) {
            return false;
        }
        if (status != WorkbenchStageStatus.HUMAN_COMPLETED) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_TRANSITION_INVALID,
                    "only a human-completed Stage can be reopened");
        }
        status = currentConversation() == null
                ? WorkbenchStageStatus.NOT_STARTED
                : WorkbenchStageStatus.IN_PROGRESS;
        completedAt = null;
        lastActivityAt = reopenedTime;
        return true;
    }

    public void requireConversationsCreatedBy(OwnerReference owner) {
        for (WorkbenchStageConversationReference conversation
                : conversationHistory) {
            if (!conversation.getCreatedBy().sameIdentityAs(owner)) {
                throw new IllegalArgumentException(
                        "Stage conversation creator must match Workbench owner");
            }
        }
    }

    private void validateState() {
        if (conversationHistory.isEmpty()) {
            if (conversationGeneration != 0) {
                throw new IllegalArgumentException(
                        "Empty Stage conversation history must use generation zero");
            }
        } else {
            if (conversationHistory.size() != conversationGeneration + 1) {
                throw new IllegalArgumentException(
                        "Stage conversation history must contain every generation");
            }
            for (int index = 0; index < conversationHistory.size(); index++) {
                WorkbenchStageConversationReference conversation =
                        conversationHistory.get(index);
                if (conversation.getGeneration() != index) {
                    throw new IllegalArgumentException(
                            "Stage conversation generations must be contiguous");
                }
                boolean latest = index == conversationHistory.size() - 1;
                if (latest != conversation.isActive()) {
                    throw new IllegalArgumentException(
                            "Only the latest Stage conversation can be active");
                }
            }
        }
        if (status == WorkbenchStageStatus.IN_PROGRESS
                && currentConversation() == null) {
            throw new IllegalArgumentException(
                    "In-progress Stage requires an active conversation");
        }
        if (activeRunReference != null
                && (status != WorkbenchStageStatus.IN_PROGRESS
                || currentConversation() == null
                || lastActivityAt == null
                || lastActivityAt.isBefore(
                        activeRunReference.getPreparedAt()))) {
            throw new IllegalArgumentException(
                    "Active Stage Run requires an in-progress conversation");
        }
    }

    private static boolean containsNull(
            List<WorkbenchStageConversationReference> conversations) {
        for (WorkbenchStageConversationReference conversation : conversations) {
            if (conversation == null) {
                return true;
            }
        }
        return false;
    }

    private static WorkbenchStageRunReference restoreRunReference(
            String runIdentifier, RunMode runMode, Instant preparedAt) {
        boolean allAbsent = runIdentifier == null
                && runMode == null && preparedAt == null;
        boolean allPresent = runIdentifier != null
                && runMode != null && preparedAt != null;
        if (!allAbsent && !allPresent) {
            throw new IllegalArgumentException(
                    "Stage Instance active Run facts must be complete");
        }
        return allPresent
                ? WorkbenchStageRunReference.restore(
                        runIdentifier, runMode, preparedAt)
                : null;
    }
}
