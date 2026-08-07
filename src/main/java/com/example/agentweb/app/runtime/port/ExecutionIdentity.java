package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 一次物理 Runtime 执行的稳定身份与业务来源引用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class ExecutionIdentity {

    private final String executionId;
    private final String ownerId;
    private final String originReference;
    private final String conversationId;
    private final long userMessageId;

    public ExecutionIdentity(String executionId, String ownerId, String originReference) {
        this(executionId, ownerId, originReference, null, -1L);
    }

    public ExecutionIdentity(String executionId, String ownerId, String originReference,
                             String conversationId, long userMessageId) {
        this.executionId = DomainText.require(executionId, "execution id", 160);
        this.ownerId = DomainText.require(ownerId, "execution owner id", 160);
        this.originReference = DomainText.require(
                originReference, "execution origin reference", 500);
        this.conversationId = conversationId == null || conversationId.trim().isEmpty()
                ? null : conversationId.trim();
        if (userMessageId < -1L) {
            throw new IllegalArgumentException("execution user message id is invalid");
        }
        this.userMessageId = userMessageId;
    }
}
