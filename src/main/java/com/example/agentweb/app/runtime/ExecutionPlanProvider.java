package com.example.agentweb.app.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.RunOrigin;

/**
 * 根据已持久化 ChatRun 来源事实构造一次不可变 Runtime 执行计划。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ExecutionPlanProvider {

    boolean supports(RunOrigin origin);

    AgentExecutionPlan prepare(ChatRun run);
}
