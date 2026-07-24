package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * Harness 阶段对话时间线中的只读消息。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class HarnessConversationMessageView {

    private final String messageId;
    private final String role;
    private final String stage;
    private final int attemptNumber;
    private final String content;
    private final String contentType;
    private final String artifactType;
    private final long createdAt;

    public HarnessConversationMessageView(String messageId, String role, String stage,
                                          int attemptNumber, String content,
                                          String contentType, String artifactType,
                                          long createdAt) {
        this.messageId = messageId;
        this.role = role;
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.content = content;
        this.contentType = contentType;
        this.artifactType = artifactType;
        this.createdAt = createdAt;
    }
}
