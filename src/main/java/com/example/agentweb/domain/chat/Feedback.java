package com.example.agentweb.domain.chat;

import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * 会话级反馈值对象:用户对该对话 AI 分析正确性的评分与文字补充。
 * <p>rating 与 comment 均可空——允许只评分不写字、或只留备注不评分;
 * 整体替换语义,由调用方组装完整对象后落库。</p>
 * @author zhourui(V33215020)
 * @since 2026-05-20
 */
public final class Feedback {

    @Getter
    private final FeedbackRating rating;
    @Getter
    private final String comment;
    @Getter
    private final Instant updatedAt;

    @JsonCreator
    public Feedback(@JsonProperty("rating") FeedbackRating rating,
                    @JsonProperty("comment") String comment,
                    @JsonProperty("updatedAt") Instant updatedAt) {
        this.rating = rating;
        this.comment = comment;
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Feedback other = (Feedback) o;
        return rating == other.rating
                && Objects.equals(comment, other.comment)
                && Objects.equals(updatedAt, other.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rating, comment, updatedAt);
    }
}
