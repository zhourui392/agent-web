package com.example.agentweb.app.workbench.query;

import lombok.Getter;

/**
 * 当前动态 Stage Conversation 消息的有界反向游标请求。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationMessageRequest {

    public static final int MAX_LIMIT = 50;

    private final Long beforeMessageId;
    private final int limit;

    public WorkbenchStageConversationMessageRequest(
            Long beforeMessageId, int limit) {
        if (beforeMessageId != null && beforeMessageId.longValue() <= 0L) {
            throw new IllegalArgumentException(
                    "stage conversation message cursor must be positive");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "stage conversation message limit must be between 1 and "
                            + MAX_LIMIT);
        }
        this.beforeMessageId = beforeMessageId;
        this.limit = limit;
    }

    public static WorkbenchStageConversationMessageRequest latest() {
        return new WorkbenchStageConversationMessageRequest(null, MAX_LIMIT);
    }
}
