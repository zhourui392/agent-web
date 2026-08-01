package com.example.agentweb.domain.chatrun;

/**
 * ChatRun 对重启恢复事实作出的领域决策。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum ChatRunRecoveryDecision {
    RETAIN_ACTIVE,
    FINALIZE_TERMINATION,
    STOP_AND_INTERRUPT,
    INTERRUPT,
    IGNORE_TERMINAL
}
