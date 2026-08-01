package com.example.agentweb.domain.chat;

/**
 * 持久化会话的中性来源种类，不携带 Workbench 阶段或运行策略。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum SessionKind {
    CHAT,
    WORKBENCH_PHASE
}
