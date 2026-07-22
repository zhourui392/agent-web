package com.example.agentweb.app.chatrun;

import lombok.Getter;

/**
 * Authenticated command to submit one chat run.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class SubmitChatRunCommand {

    private final String sessionId;
    private final String message;
    private final String resumeId;
    private final boolean recallEnabled;
    private final String idempotencyKey;

    public SubmitChatRunCommand(String sessionId, String message, String resumeId,
                                boolean recallEnabled, String idempotencyKey) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()
                || idempotencyKey.trim().length() > 128) {
            throw new InvalidIdempotencyKeyException();
        }
        this.sessionId = sessionId.trim();
        this.message = message;
        this.resumeId = resumeId;
        this.recallEnabled = recallEnabled;
        this.idempotencyKey = idempotencyKey.trim();
    }
}
