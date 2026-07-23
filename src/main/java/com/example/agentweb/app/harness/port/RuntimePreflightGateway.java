package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.AgentRuntime;

/**
 * 采集目标 Runtime 实际可强制能力的出站端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface RuntimePreflightGateway {

    /**
     * 获取目标 Runtime 当前可强制的安全能力。
     *
     * @param runtime Runtime 类型
     * @param workingDir 工作目录
     * @return 强制能力与工作区清单
     */
    RuntimePreflightReport preflight(AgentRuntime runtime, String workingDir);
}
