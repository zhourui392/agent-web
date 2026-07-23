package com.example.agentweb.app;

import com.example.agentweb.domain.shared.AgentType;

/**
 * 对话默认 Agent 读取端口。运行时配置和持久化细节由 Infrastructure 实现。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface ChatAgentDefaults {

    AgentType getChatDefaultAgent();
}
