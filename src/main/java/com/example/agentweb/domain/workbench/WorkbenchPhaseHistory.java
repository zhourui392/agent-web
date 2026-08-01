package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * 当前 Phase Session 的有界、已格式化历史投影。
 *
 * <p>该值同时携带 Session 与 Context 身份，防止准备流程把其他 Phase 或退役
 * Session 的历史注入当前运行。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchPhaseHistory {

    private static final int MAXIMUM_BYTES = 512 * 1024;

    private final String sessionId;
    private final String contextId;
    private final String content;
    private final WorkbenchPromptHistoryDelivery delivery;

    private WorkbenchPhaseHistory(
            String sessionId, String contextId, String content,
            WorkbenchPromptHistoryDelivery delivery) {
        this.sessionId = DomainText.require(
                sessionId, "workbench phase history session id", 128);
        this.contextId = DomainText.require(
                contextId, "workbench phase history context id", 512);
        if (content == null) {
            throw new IllegalArgumentException(
                    "workbench phase history content must not be null");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "workbench phase history exceeds the 512 KiB limit");
        }
        if (delivery == null) {
            throw new IllegalArgumentException(
                    "workbench phase history delivery must not be null");
        }
        this.content = content;
        this.delivery = delivery;
    }

    public static WorkbenchPhaseHistory freeze(
            String sessionId, String contextId, String content,
            WorkbenchPromptHistoryDelivery delivery) {
        return new WorkbenchPhaseHistory(
                sessionId, contextId, content, delivery);
    }

    public boolean hasContent() {
        return !content.trim().isEmpty();
    }

    public void requireCurrent(PhaseConversationProvisioning provisioning) {
        if (provisioning == null
                || !sessionId.equals(
                provisioning.requireCurrentConversationId())
                || !contextId.equals(provisioning.getContextId())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }
}
