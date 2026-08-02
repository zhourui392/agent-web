package com.example.agentweb.domain.chatrun;

/**
 * ChatRun 对重启恢复事实作出的领域决策。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum ChatRunRecoveryDecision {
    RETAIN_ACTIVE(false),
    FINALIZE_TERMINATION(true),
    STOP_AND_INTERRUPT(true),
    INTERRUPT(true),
    IGNORE_TERMINAL(false);

    private final boolean recoveryApplied;

    ChatRunRecoveryDecision(boolean recoveryApplied) {
        this.recoveryApplied = recoveryApplied;
    }

    public boolean isRecoveryApplied() {
        return recoveryApplied;
    }
}
