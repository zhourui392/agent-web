package com.example.agentweb.app.chat;

/**
 * 创建聊天会话的应用命令。
 *
 * @author alex
 * @since 2026-07-23
 */
public record StartSessionCommand(String agentType, String workingDir, String env) {
}
