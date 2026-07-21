package com.example.agentweb.infra.cli;

/**
 * {@link AgentCliInvoker} 同步调用的统一失败包装。
 *
 * <p>覆盖三类失败:</p>
 * <ul>
 *   <li>{@link Reason#TIMEOUT} 进程在 {@code timeoutSeconds} 内未退出</li>
 *   <li>{@link Reason#NON_ZERO_EXIT} 进程退出但 exit code != 0</li>
 *   <li>{@link Reason#IO_FAILURE} 进程启动失败 / stdout 读异常 / 被中断</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public class CliInvokeException extends RuntimeException {

    public enum Reason {
        TIMEOUT,
        NON_ZERO_EXIT,
        IO_FAILURE
    }

    private final Reason reason;

    public CliInvokeException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public CliInvokeException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
