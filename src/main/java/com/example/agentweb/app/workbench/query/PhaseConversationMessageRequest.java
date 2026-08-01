package com.example.agentweb.app.workbench.query;

import lombok.Getter;

/**
 * 当前 Phase Conversation 消息的有界反向游标请求。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationMessageRequest {

    public static final int MAX_LIMIT = 50;

    private final Long beforeMessageId;
    private final int limit;

    public PhaseConversationMessageRequest(
            Long beforeMessageId, int limit) {
        if (beforeMessageId != null && beforeMessageId.longValue() <= 0L) {
            throw new IllegalArgumentException(
                    "phase conversation message cursor must be positive");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "phase conversation message limit must be between 1 and "
                            + MAX_LIMIT);
        }
        this.beforeMessageId = beforeMessageId;
        this.limit = limit;
    }

    public static PhaseConversationMessageRequest latest() {
        return new PhaseConversationMessageRequest(null, MAX_LIMIT);
    }
}
