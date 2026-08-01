package com.example.agentweb.app.runtime.port;

/**
 * Runtime Handle 的技术状态，不代表任何业务聚合状态。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RuntimeState {
    RUNNING,
    STOP_REQUESTED,
    TERMINATED,
    NOT_FOUND
}
