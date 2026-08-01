package com.example.agentweb.infra.runtime;

import lombok.Getter;

/**
 * 有界版本探测的进程退出码和输出；只在 Adapter 内部解析。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
final class RuntimeVersionProbeResult {

    private final int exitCode;
    private final String output;

    RuntimeVersionProbeResult(int exitCode, String output) {
        if (output == null) {
            throw new IllegalArgumentException(
                    "runtime version probe output must not be null");
        }
        this.exitCode = exitCode;
        this.output = output;
    }
}
