package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一次物理执行的硬超时、输出上限和可继承环境变量白名单。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeLimits {

    private static final Pattern ENVIRONMENT_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Duration timeout;
    private final long maxOutputBytes;
    private final Set<String> environmentAllowlist;

    public RuntimeLimits(Duration timeout, long maxOutputBytes,
                         Set<String> environmentAllowlist) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("runtime timeout must be positive");
        }
        if (maxOutputBytes < 1L) {
            throw new IllegalArgumentException("runtime max output bytes must be positive");
        }
        if (environmentAllowlist == null || environmentAllowlist.contains(null)) {
            throw new IllegalArgumentException(
                    "runtime environment allowlist must not be null or contain null");
        }
        LinkedHashSet<String> copied = new LinkedHashSet<String>();
        for (String name : environmentAllowlist) {
            if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "runtime environment allowlist contains an invalid name");
            }
            copied.add(name);
        }
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.environmentAllowlist = Collections.unmodifiableSet(copied);
    }
}
