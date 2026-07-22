package com.example.agentweb.domain.chatrun;

/**
 * Raised when a chat run is asked to perform an illegal lifecycle transition.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class IllegalChatRunTransitionException extends RuntimeException {

    public IllegalChatRunTransitionException(ChatRunStatus current, ChatRunStatus target) {
        super("illegal chat run transition: " + current + " -> " + target);
    }
}
