package com.example.agentweb.app.runtime.port;

/**
 * Provider 中立的技术终止原因。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RuntimeTerminationReason {
    COMPLETED,
    REQUESTED_STOP,
    TIMEOUT,
    OUTPUT_LIMIT,
    SECURITY_POLICY,
    START_FAILURE,
    PROCESS_FAILURE
}
