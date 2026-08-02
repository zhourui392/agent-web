package com.example.agentweb.app.runtime.port;

/**
 * Runtime Preflight 的稳定技术拒绝原因，不携带 Provider 输出或本机路径。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RuntimePreflightErrorCode {
    RUNTIME_UNSUPPORTED,
    RUNTIME_PROBE_START_FAILED,
    RUNTIME_PROBE_TIMEOUT,
    RUNTIME_PROBE_OUTPUT_LIMIT_EXCEEDED,
    RUNTIME_PROBE_INTERRUPTED,
    RUNTIME_PROBE_FAILED,
    RUNTIME_VERSION_MALFORMED,
    RUNTIME_VERSION_MISMATCH,
    RUNTIME_COMPATIBILITY_MISMATCH,
    RUNTIME_LAYOUT_UNSUPPORTED
}
