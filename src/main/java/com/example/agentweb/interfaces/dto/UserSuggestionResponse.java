package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.suggestion.UserSuggestion;
import lombok.Getter;

/**
 * 用户建议响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Getter
public class UserSuggestionResponse {

    private final String id;
    private final String userId;
    private final String userName;
    private final String title;
    private final String content;
    private final String contact;
    private final String status;
    private final String statusLabel;
    private final String adminReply;
    private final String createdAt;
    private final String updatedAt;
    private final String repliedAt;

    public UserSuggestionResponse(String id, String userId, String userName, String title,
                                  String content, String contact, String status, String statusLabel,
                                  String adminReply, String createdAt, String updatedAt, String repliedAt) {
        this.id = id;
        this.userId = userId;
        this.userName = userName;
        this.title = title;
        this.content = content;
        this.contact = contact;
        this.status = status;
        this.statusLabel = statusLabel;
        this.adminReply = adminReply;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.repliedAt = repliedAt;
    }

    public static UserSuggestionResponse from(UserSuggestion suggestion) {
        return new UserSuggestionResponse(
                suggestion.getId(),
                suggestion.getUserId(),
                suggestion.getUserName(),
                suggestion.getTitle(),
                suggestion.getContent(),
                suggestion.getContact(),
                suggestion.getStatus().name(),
                suggestion.getStatus().getLabel(),
                suggestion.getAdminReply(),
                suggestion.getCreatedAt().toString(),
                suggestion.getUpdatedAt().toString(),
                suggestion.getRepliedAt() == null ? null : suggestion.getRepliedAt().toString());
    }
}
