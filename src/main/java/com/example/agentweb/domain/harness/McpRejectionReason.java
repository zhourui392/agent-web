package com.example.agentweb.domain.harness;

/**
 * MCP 整服拒绝的稳定原因。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum McpRejectionReason {
    /** 请求未获得 Run Grant。 */
    NOT_GRANTED,
    /** 目标环境未允许该 Server。 */
    ENVIRONMENT_DENIED,
    /** Server 不适用于当前阶段。 */
    STAGE_INCOMPATIBLE,
    /** Server 不适用于当前 Runtime。 */
    RUNTIME_INCOMPATIBLE,
    /** M3 不允许写能力。 */
    WRITE_NOT_SUPPORTED,
    /** 当前 Runtime 未证明 MCP Resource 可被精确隔离。 */
    RESOURCE_NOT_SUPPORTED,
    /** Runtime 无法执行精确权限约束。 */
    ENFORCEMENT_INSUFFICIENT
}
