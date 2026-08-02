package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Locale;

/**
 * Candidate 生成可消费的单条公开 Phase Conversation 消息。
 *
 * <p>只接受最终用户与最终 Agent 可展示消息；Tool、System 和内部事件在进入
 * Candidate 策略前即被拒绝。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffCandidateMessage {

    private static final int MAX_CONTENT_CHARS = 1024 * 1024;

    public enum Role {
        USER,
        ASSISTANT
    }

    private final long messageId;
    private final Role role;
    private final String content;
    private final String runId;

    private HandoffCandidateMessage(
            long messageId, Role role, String content, String runId) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException(
                    "handoff candidate message id must be positive");
        }
        this.messageId = messageId;
        this.role = role;
        this.content = WorkbenchText.allowEmptyUntrustedText(
                content, "handoff candidate public message", MAX_CONTENT_CHARS);
        this.runId = optionalRunId(runId);
    }

    public static HandoffCandidateMessage publicMessage(
            long messageId, String role, String content, String runId) {
        return new HandoffCandidateMessage(
                messageId, requirePublicRole(role), content, runId);
    }

    private static Role requirePublicRole(String value) {
        String normalized = DomainText.require(
                value, "handoff candidate message role", 32)
                .toUpperCase(Locale.ROOT);
        try {
            return Role.valueOf(normalized);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "handoff candidate accepts only public user/assistant messages");
        }
    }

    private static String optionalRunId(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return DomainText.require(value, "handoff candidate run id", 128);
    }
}
