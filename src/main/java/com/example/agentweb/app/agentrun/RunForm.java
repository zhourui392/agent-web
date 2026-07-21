package com.example.agentweb.app.agentrun;

/**
 * Agent run execution form. It drives execution-pipeline behavior, not business meaning.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public enum RunForm {
    CHAT,
    // 诊断子系统已摘除，DIAGNOSE 为历史枚举，无活跃生产者，保留以兼容既有召回策略与测试夹具。
    DIAGNOSE,
    WORKFLOW_STEP,
    SCHEDULED,
    CUSTOM,
    // 需求线 run 形态(M2 计划门起使用)
    PLAN,
    IMPLEMENT,
    FIX
}
