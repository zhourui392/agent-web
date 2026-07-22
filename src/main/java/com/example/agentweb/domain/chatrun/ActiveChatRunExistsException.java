package com.example.agentweb.domain.chatrun;

/**
 * Raised when a session already owns an active chat run.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class ActiveChatRunExistsException extends RuntimeException {

    public ActiveChatRunExistsException(String sessionId) {
        super("active chat run already exists for session: " + sessionId);
    }
}
