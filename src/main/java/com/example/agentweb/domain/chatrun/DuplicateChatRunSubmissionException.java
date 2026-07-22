package com.example.agentweb.domain.chatrun;

/**
 * Raised when a session/idempotency-key pair has already been persisted.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class DuplicateChatRunSubmissionException extends RuntimeException {

    public DuplicateChatRunSubmissionException(String sessionId, String idempotencyKey) {
        super("duplicate chat run submission for session " + sessionId + " and idempotency key "
                + idempotencyKey);
    }
}
