package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 单条对话记录详情:摘要行 + 完整消息流(只读投影,供 admin 查看)。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Getter
@Setter
public class ConversationDetail {

    private ConversationRecord record;
    private List<ConversationMessage> messages;
}
