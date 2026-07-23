package com.example.agentweb.app.harness.port;

/**
 * Harness 专用外部 Agent Runtime 出站端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface AgentRuntimeGateway {

    /**
     * 根据已提交的不可变执行规格启动 Runtime。
     *
     * @param spec 执行规格
     * @param eventSink 归一化事件接收端
     */
    void start(AgentExecutionSpec spec, RuntimeEventSink eventSink);

    /**
     * 终止指定活动执行；不存在时保持幂等。
     *
     * @param executionId Execution ID
     */
    void cancel(String executionId);
}
