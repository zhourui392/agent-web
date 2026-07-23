package com.example.agentweb.domain.harness;

/**
 * Stage 当前有效状态，和历史 Attempt 状态分离。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum StageStatus {
    PENDING,
    RUNNING,
    WAITING_INPUT,
    WAITING_APPROVAL,
    CANCELLING,
    PASSED,
    FAILED,
    INVALIDATED,
    CANCELLED;

    /**
     * 是否占用 Run 的唯一写 Attempt。
     *
     * @return 是否可写
     */
    public boolean isWritable() {
        return this == RUNNING || this == WAITING_INPUT || this == WAITING_APPROVAL;
    }

    /**
     * 是否仍占用 Run 的唯一活动 Attempt。
     *
     * @return 是否活动
     */
    public boolean occupiesActiveAttempt() {
        return isWritable() || this == CANCELLING;
    }
}
