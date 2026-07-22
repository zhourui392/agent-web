package com.example.agentweb.domain.chatrun;

/**
 * Chat run lifecycle status.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public enum ChatRunStatus {
    PENDING,
    RUNNING,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == CANCEL_REQUESTED;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
