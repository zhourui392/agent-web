package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Locale;

/**
 * Review Candidate 策略允许消费的一条公开会话消息。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewCandidateMessage {

    private static final int MAX_CONTENT_CHARS = 1024 * 1024;

    public enum Role {
        USER,
        ASSISTANT
    }

    private final long messageId;
    private final Role role;
    private final String content;

    private ReviewCandidateMessage(
            long messageId, Role role, String content) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException(
                    "review candidate message id must be positive");
        }
        this.messageId = messageId;
        this.role = role;
        this.content = WorkbenchText.allowEmptyUntrustedText(
                content, "review candidate public message",
                MAX_CONTENT_CHARS);
    }

    public static ReviewCandidateMessage publicMessage(
            long messageId, String role, String content) {
        return new ReviewCandidateMessage(
                messageId, requirePublicRole(role), content);
    }

    private static Role requirePublicRole(String value) {
        String normalized = DomainText.require(
                value, "review candidate message role", 32)
                .toUpperCase(Locale.ROOT);
        try {
            return Role.valueOf(normalized);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "review candidate accepts only public user/assistant messages");
        }
    }
}
