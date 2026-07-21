package com.example.agentweb.domain.refinery;

import lombok.Getter;
import java.time.Instant;

/**
 * 单条对话回合 (role + 内容 + 时间). 不变量: timestamp 不可为 null.
 *
 * <p>role / content 容忍 null, 归一化为空串以便后续拼装 prompt 时无需空判.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
public final class ConversationTurn {

    @Getter
    private final String role;
    @Getter
    private final String content;
    @Getter
    private final Instant timestamp;

    public ConversationTurn(String role, String content, Instant timestamp) {
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        this.role = role == null ? "" : role;
        this.content = content == null ? "" : content;
        this.timestamp = timestamp;
    }
}
