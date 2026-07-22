package com.example.agentweb.app.chatrun;

/**
 * Raised when the configured global active ChatRun capacity is exhausted.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class RunCapacityExceededException extends RuntimeException {

    public RunCapacityExceededException(int capacity) {
        super("active chat run capacity reached: " + capacity);
    }
}
