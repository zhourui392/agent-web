package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * 当前 Dynamic Stage Session 的有界、已格式化历史投影。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationHistory {

    private static final int MAXIMUM_BYTES = 512 * 1024;

    private final String sessionId;
    private final String contextId;
    private final int conversationGeneration;
    private final String content;
    private final WorkbenchPromptHistoryDelivery delivery;

    private WorkbenchStageConversationHistory(
            String sessionId, String contextId,
            int conversationGeneration, String content,
            WorkbenchPromptHistoryDelivery delivery) {
        this.sessionId = DomainText.require(
                sessionId, "Workbench Stage history Session identifier", 128);
        this.contextId = DomainText.require(
                contextId, "Workbench Stage history Context identifier", 512);
        if (conversationGeneration < 0) {
            throw new IllegalArgumentException(
                    "Workbench Stage history generation must not be negative");
        }
        if (content == null
                || content.getBytes(StandardCharsets.UTF_8).length
                > MAXIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "Workbench Stage history must not be null or exceed 512 KiB");
        }
        if (delivery == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage history delivery is required");
        }
        this.conversationGeneration = conversationGeneration;
        this.content = content;
        this.delivery = delivery;
    }

    public static WorkbenchStageConversationHistory freeze(
            String sessionId, String contextId,
            int conversationGeneration, String content,
            WorkbenchPromptHistoryDelivery delivery) {
        return new WorkbenchStageConversationHistory(
                sessionId, contextId, conversationGeneration,
                content, delivery);
    }

    public boolean hasContent() {
        return !content.trim().isEmpty();
    }

    public void requireCurrent(
            WorkbenchStageConversationProvisioning provisioning) {
        if (provisioning == null
                || !sessionId.equals(
                provisioning.requireCurrentConversationId())
                || !contextId.equals(provisioning.getContextId())
                || conversationGeneration
                != provisioning.getCurrentConversationGeneration()) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }
}
