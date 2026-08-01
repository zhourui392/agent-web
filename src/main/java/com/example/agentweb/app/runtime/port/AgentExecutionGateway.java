package com.example.agentweb.app.runtime.port;

/**
 * Provider 中立的异步 Agent Runtime 出站端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface AgentExecutionGateway {

    RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink);

    void requestStop(RuntimeHandle handle);

    RuntimeObservation observe(RuntimeHandle handle);
}
