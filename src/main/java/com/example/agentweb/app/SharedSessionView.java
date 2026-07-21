package com.example.agentweb.app;

import lombok.Value;

import java.util.List;

/**
 * 分享页会话读模型（公开链接访问，无鉴权），供 {@code /api/share/{token}} 返回。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Value
public class SharedSessionView {

    String title;
    String agentType;
    String workingDir;
    String createdAt;
    List<ChatMessageView> messages;
}
