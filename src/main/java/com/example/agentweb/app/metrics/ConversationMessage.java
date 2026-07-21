package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

/**
 * 对话记录详情中的单条消息(只读投影)。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Getter
@Setter
public class ConversationMessage {

    /** 角色:user / assistant / system 等。 */
    private String role;
    private String content;
    /** 时间 ISO-8601 字符串。 */
    private String timestamp;
}
