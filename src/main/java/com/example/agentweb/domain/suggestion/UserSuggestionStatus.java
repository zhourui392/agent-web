package com.example.agentweb.domain.suggestion;

/**
 * 用户建议的处理状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public enum UserSuggestionStatus {

    /** 新提交,尚未处理。 */
    PENDING("待处理"),

    /** 已开始处理。 */
    PROCESSING("处理中"),

    /** 已回复用户。 */
    REPLIED("已回复"),

    /** 已关闭。 */
    CLOSED("已关闭");

    private final String label;

    UserSuggestionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static UserSuggestionStatus parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return UserSuggestionStatus.valueOf(raw.trim().toUpperCase());
    }
}
