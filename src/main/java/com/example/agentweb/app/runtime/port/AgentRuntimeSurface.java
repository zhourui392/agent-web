package com.example.agentweb.app.runtime.port;

/**
 * Runtime 调用来源，用于限制 Profile 的可见范围。
 *
 * @author alex
 * @since 2026-08-07
 */
public enum AgentRuntimeSurface {
    CHAT,
    WORKBENCH,
    SCHEDULE,
    REFINERY
}
