package com.example.agentweb.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Value;

/**
 * 会话消息读模型（CQRS 读侧 DTO），含随消息回放的召回卡片 JSON。
 *
 * <p>字段名即前端 JSON 契约（原 {@code MessageDto} 形状），改名即破坏兼容。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Value
public class ChatMessageView {

    Long id;
    String role;
    String content;
    String timestamp;

    /** 召回回放 JSON {@code {query,status,hits:[...]}}；仅命中过召回的 assistant 消息非空。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String recall;
}
