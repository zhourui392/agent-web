package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.time.Duration;

/**
 * 一次物理执行的硬超时和输出上限。
 *
 * <p>单用户本机模式下 Codex 子进程直接继承服务进程环境变量，
 * 不再维护可继承环境变量白名单。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeLimits {

    private final Duration timeout;
    private final long maxOutputBytes;

    public RuntimeLimits(Duration timeout, long maxOutputBytes) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("runtime timeout must be positive");
        }
        if (maxOutputBytes < 1L) {
            throw new IllegalArgumentException("runtime max output bytes must be positive");
        }
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
    }
}
