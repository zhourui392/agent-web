package com.example.agentweb.app;

/**
 * 发送单条聊天消息的应用命令。
 *
 * @author alex
 * @since 2026-07-23
 */
public record SendMessageCommand(String message) {
}
