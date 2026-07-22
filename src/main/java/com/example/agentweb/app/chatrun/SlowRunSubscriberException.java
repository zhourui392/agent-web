package com.example.agentweb.app.chatrun;

/**
 * Signals that a subscriber exceeded its bounded live queue.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class SlowRunSubscriberException extends RuntimeException {

    public SlowRunSubscriberException(String runId) {
        super("run subscriber is too slow: " + runId);
    }
}
