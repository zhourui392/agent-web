package com.example.agentweb.app;

import lombok.Value;

/**
 * 会话列表页摘要读模型（CQRS 读侧 DTO）。
 *
 * <p>字段名即前端 JSON 契约（原 {@code Map<String,Object>} 键名），改名即破坏兼容。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Value
public class ChatSessionSummary {

    String sessionId;
    String agentType;
    String workingDir;
    String createdAt;
    String resumeId;
    String env;
    int messageCount;

    /** 会话归属(创建者), 供前端按归属隐藏删除按钮; 为空表示老数据/公共会话。 */
    String userId;

    /** 列表标题：session.title 或首条用户消息，超 50 字截断。 */
    String title;
}
