package com.example.agentweb.app.runtime.port;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Objects;

/**
 * 已终止 Runtime 的退出码和技术原因。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class RuntimeTermination {

    private final int exitCode;
    private final RuntimeTerminationReason reason;

    public RuntimeTermination(int exitCode, RuntimeTerminationReason reason) {
        this.exitCode = exitCode;
        this.reason = Objects.requireNonNull(reason, "reason");
    }
}
