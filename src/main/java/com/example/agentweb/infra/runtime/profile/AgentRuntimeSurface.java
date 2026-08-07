package com.example.agentweb.infra.runtime.profile;

/** Runtime 调用来源，用于限制 Profile 的可见范围。 */
public enum AgentRuntimeSurface {
    CHAT,
    WORKBENCH,
    SCHEDULE,
    WORKFLOW,
    REFINERY
}
