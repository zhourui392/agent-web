package com.example.agentweb.domain.harness;

/**
 * Harness Run 粗粒度生命周期状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum HarnessRunStatus {
    DRAFT,
    ACTIVE,
    WAITING_INPUT,
    WAITING_APPROVAL,
    CANCELLING,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
    COMPLETED,
    CANCELLED;

    /**
     * 普通动作不可再改变的终态。
     *
     * @return 是否终态
     */
    public boolean isTerminal() {
        return this == ROLLED_BACK || this == COMPLETED || this == CANCELLED;
    }
}
