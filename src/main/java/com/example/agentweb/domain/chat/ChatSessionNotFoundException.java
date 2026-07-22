package com.example.agentweb.domain.chat;

/**
 * Raised when a chat session does not exist or is invisible to the current user.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class ChatSessionNotFoundException extends RuntimeException {

    public ChatSessionNotFoundException(String sessionId) {
        super("chat session not found: " + sessionId);
    }
}
