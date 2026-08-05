package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceipt;
import lombok.Getter;

/**
 * 动态 Stage Conversation ensure/restart 的轻量事务结果。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationResult {

    private final String workbenchId;
    private final String stageInstanceIdentifier;
    private final String definitionIdentifier;
    private final String previousSessionId;
    private final String sessionId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final boolean created;
    private final boolean replayed;

    private WorkbenchStageConversationResult(
            String workbenchId, String stageInstanceIdentifier,
            String definitionIdentifier, String previousSessionId,
            String sessionId, int conversationGeneration,
            long workbenchVersion, boolean created, boolean replayed) {
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = stageInstanceIdentifier;
        this.definitionIdentifier = definitionIdentifier;
        this.previousSessionId = previousSessionId;
        this.sessionId = sessionId;
        this.conversationGeneration = conversationGeneration;
        this.workbenchVersion = workbenchVersion;
        this.created = created;
        this.replayed = replayed;
    }

    static WorkbenchStageConversationResult existing(
            WorkbenchStageConversationProvisioning provisioning) {
        requireCurrent(provisioning);
        return fromProvisioning(provisioning, null, false, false);
    }

    static WorkbenchStageConversationResult changed(
            WorkbenchStageConversationProvisioning provisioning,
            String previousSessionId) {
        requireCurrent(provisioning);
        return fromProvisioning(
                provisioning, previousSessionId, true, false);
    }

    static WorkbenchStageConversationResult replayed(
            WorkbenchStageConversationRestartReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "Stage restart receipt is required");
        }
        return new WorkbenchStageConversationResult(
                receipt.getWorkbenchId().getValue(),
                receipt.getStageInstanceIdentifier(), null,
                receipt.getPreviousSessionId(), receipt.getSessionId(),
                receipt.getConversationGeneration(),
                receipt.getWorkbenchVersion(), false, true);
    }

    private static WorkbenchStageConversationResult fromProvisioning(
            WorkbenchStageConversationProvisioning provisioning,
            String previousSessionId, boolean created, boolean replayed) {
        return new WorkbenchStageConversationResult(
                provisioning.getWorkbenchId().getValue(),
                provisioning.getStageInstanceIdentifier(),
                provisioning.getDefinitionIdentifier(), previousSessionId,
                provisioning.getCurrentConversationId(),
                provisioning.getCurrentConversationGeneration(),
                provisioning.getWorkbenchVersion(), created, replayed);
    }

    private static void requireCurrent(
            WorkbenchStageConversationProvisioning provisioning) {
        if (provisioning == null || !provisioning.hasCurrentConversation()) {
            throw new IllegalArgumentException(
                    "Current Stage conversation is required");
        }
    }
}
