package com.example.agentweb.domain.harness;

/**
 * 一次外部 Runtime 执行的生命周期状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum RuntimeExecutionStatus {
    /** 执行记录已提交，尚未启动。 */
    PREPARED,
    /** 启动意图已提交。 */
    STARTING,
    /** 进程已确认运行。 */
    RUNNING,
    /** 取消意图已提交。 */
    CANCEL_REQUESTED,
    /** 成功终态。 */
    SUCCEEDED,
    /** 失败终态。 */
    FAILED,
    /** 超时终态。 */
    TIMED_OUT,
    /** 取消终态。 */
    CANCELLED,
    /** 丢失终态。 */
    LOST;

    /**
     * @return 是否已进入不可重放的终态
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT
                || this == CANCELLED || this == LOST;
    }
}
