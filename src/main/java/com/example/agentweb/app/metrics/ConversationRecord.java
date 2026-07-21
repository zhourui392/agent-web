package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

/**
 * 管理后台「所有用户的对话记录」列表行(纯投影 DTO,非聚合)。
 *
 * <p>跨全部用户,不做 user_id 隔离;{@code title} 为空时由首条 user 消息兜底。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Getter
@Setter
public class ConversationRecord {

    private String sessionId;
    private String agentType;
    /** 展示标题:优先 chat_session.title,空则取首条 user 消息内容截断。 */
    private String title;
    /** 创建者登录用户 ID；老数据或系统会话为 null。 */
    private String userId;
    /** 创建者展示名(如 周锐);仅审计用。 */
    private String userName;
    private String clientIp;
    private long messageCount;
    /** 创建时间 ISO-8601 字符串。 */
    private String createdAt;
    /** 最后一条消息时间(epoch millis);从未发消息为 null。 */
    private Long lastMessageAt;
    /** 用户评分 CORRECT / PARTIALLY_CORRECT / INCORRECT;未评分为 null。 */
    private String feedbackRating;
}
