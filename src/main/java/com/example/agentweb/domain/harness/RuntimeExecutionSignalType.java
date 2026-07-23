package com.example.agentweb.domain.harness;

/**
 * Runtime Adapter 归一化后的稳定执行信号。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum RuntimeExecutionSignalType {
    /** 外部进程已启动。 */
    STARTED,
    /** 收到一段输出。 */
    OUTPUT,
    /** 进程成功结束。 */
    SUCCEEDED,
    /** 进程失败结束。 */
    FAILED,
    /** Watchdog 确认超时。 */
    TIMED_OUT,
    /** 取消已确认。 */
    CANCELLED,
    /** 重启对账后确认执行丢失。 */
    LOST
}
