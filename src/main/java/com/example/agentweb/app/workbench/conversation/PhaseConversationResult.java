package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.domain.workbench.PhaseConversationProvisioning;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceipt;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * Phase Conversation ensure/restart 的轻量事务结果。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationResult {

    private final String workbenchId;
    private final WorkbenchPhase phase;
    private final String previousSessionId;
    private final String sessionId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final boolean created;
    private final boolean replayed;

    private PhaseConversationResult(
            String workbenchId, WorkbenchPhase phase,
            String previousSessionId, String sessionId,
            int conversationGeneration, long workbenchVersion,
            boolean created, boolean replayed) {
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.previousSessionId = previousSessionId;
        this.sessionId = sessionId;
        this.conversationGeneration = conversationGeneration;
        this.workbenchVersion = workbenchVersion;
        this.created = created;
        this.replayed = replayed;
    }

    static PhaseConversationResult existing(PhaseConversationProvisioning provisioning) {
        if (provisioning == null || !provisioning.hasCurrentConversation()) {
            throw new IllegalArgumentException("existing phase conversation is required");
        }
        return new PhaseConversationResult(
                provisioning.getWorkbenchId().getValue(), provisioning.getPhase(),
                null, provisioning.getCurrentConversationId(),
                provisioning.getCurrentConversationGeneration(),
                provisioning.getWorkbenchVersion(), false, false);
    }

    static PhaseConversationResult changed(
            PhaseConversationProvisioning provisioning,
            String previousSessionId) {
        if (provisioning == null || !provisioning.hasCurrentConversation()) {
            throw new IllegalArgumentException("changed phase conversation is required");
        }
        return new PhaseConversationResult(
                provisioning.getWorkbenchId().getValue(), provisioning.getPhase(),
                previousSessionId, provisioning.getCurrentConversationId(),
                provisioning.getCurrentConversationGeneration(),
                provisioning.getWorkbenchVersion(), true, false);
    }

    static PhaseConversationResult replayed(PhaseConversationRestartReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("restart receipt is required");
        }
        return new PhaseConversationResult(
                receipt.getWorkbenchId().getValue(), receipt.getPhase(),
                receipt.getPreviousSessionId(), receipt.getSessionId(),
                receipt.getConversationGeneration(), receipt.getWorkbenchVersion(),
                false, true);
    }
}
