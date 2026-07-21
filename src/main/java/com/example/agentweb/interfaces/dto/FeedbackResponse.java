package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import com.example.agentweb.domain.chat.Feedback;

/**
 * 会话反馈响应。从未评价过的会话各字段均为 null。
 * @author zhourui(V33215020)
 */
@Getter
public class FeedbackResponse {

    private final String rating;
    private final String comment;
    private final String updatedAt;

    public FeedbackResponse(String rating, String comment, String updatedAt) {
        this.rating = rating;
        this.comment = comment;
        this.updatedAt = updatedAt;
    }

    /**
     * 由领域反馈值对象装配响应;feedback 为 null 时返回各字段为 null 的空响应。
     * @param feedback 领域反馈值对象,可空
     * @return 反馈响应
     */
    public static FeedbackResponse from(Feedback feedback) {
        if (feedback == null) {
            return new FeedbackResponse(null, null, null);
        }
        return new FeedbackResponse(
                feedback.getRating() == null ? null : feedback.getRating().name(),
                feedback.getComment(),
                feedback.getUpdatedAt() == null ? null : feedback.getUpdatedAt().toString()
        );
    }
}
