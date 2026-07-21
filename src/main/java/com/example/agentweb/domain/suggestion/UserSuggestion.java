package com.example.agentweb.domain.suggestion;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户提交的建议/反馈工单。
 *
 * <p>这是独立于会话评分 {@code domain.chat.Feedback} 的业务对象:会话评分回答
 * "AI 分析是否正确",本对象回答"用户对系统有什么建议以及管理员如何处理"。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Getter
public final class UserSuggestion {

    private final String id;
    private final String userId;
    private final String userName;
    private final String title;
    private final String content;
    private final String contact;
    private final UserSuggestionStatus status;
    private final String adminReply;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant repliedAt;

    public UserSuggestion(String id, String userId, String userName, String title, String content, String contact,
                          UserSuggestionStatus status, String adminReply, Instant createdAt,
                          Instant updatedAt, Instant repliedAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("suggestion id 不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("建议内容不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("建议状态不能为空");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("建议时间不能为空");
        }
        this.id = id;
        this.userId = trimToNull(userId);
        this.userName = trimToNull(userName);
        this.title = trimToNull(title);
        this.content = content.trim();
        this.contact = trimToNull(contact);
        this.status = status;
        this.adminReply = trimToNull(adminReply);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.repliedAt = repliedAt;
    }

    public static UserSuggestion create(String userId, String userName, String title,
                                        String content, String contact, Instant now) {
        Instant timestamp = now == null ? Instant.now() : now;
        return new UserSuggestion(
                UUID.randomUUID().toString(),
                userId,
                userName,
                normalizeTitle(title, content),
                content,
                contact,
                UserSuggestionStatus.PENDING,
                null,
                timestamp,
                timestamp,
                null);
    }

    public UserSuggestion updateByAdmin(UserSuggestionStatus newStatus, String reply, Instant now) {
        UserSuggestionStatus targetStatus = newStatus == null ? this.status : newStatus;
        String normalizedReply = trimToNull(reply);
        Instant updateTime = now == null ? Instant.now() : now;
        Instant replyTime = this.repliedAt;
        if (normalizedReply != null && !normalizedReply.equals(this.adminReply)) {
            replyTime = updateTime;
        }
        if (normalizedReply != null && newStatus == null && this.status == UserSuggestionStatus.PENDING) {
            targetStatus = UserSuggestionStatus.REPLIED;
        }
        return new UserSuggestion(id, userId, userName, title, content, contact,
                targetStatus, normalizedReply, createdAt, updateTime, replyTime);
    }

    private static String normalizeTitle(String title, String content) {
        String t = trimToNull(title);
        if (t != null) {
            return limit(t, 80);
        }
        String c = trimToNull(content);
        if (c == null) {
            return null;
        }
        return limit(c.replace('\n', ' '), 40);
    }

    private static String limit(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
