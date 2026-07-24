package com.example.agentweb.domain.harness;

/**
 * 不可覆盖的单次阶段尝试状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum StageAttemptStatus {
    RUNNING,
    WAITING_INPUT,
    WAITING_APPROVAL,
    CANCELLING,
    SUPERSEDED,
    PASSED,
    FAILED,
    CANCELLED
}
