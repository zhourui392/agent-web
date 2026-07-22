package com.example.agentweb.domain.chatrun;

/**
 * Raised when a chat run is missing or invisible to the current user.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class ChatRunNotFoundException extends RuntimeException {

    public ChatRunNotFoundException(String runId) {
        super("chat run not found: " + runId);
    }
}
