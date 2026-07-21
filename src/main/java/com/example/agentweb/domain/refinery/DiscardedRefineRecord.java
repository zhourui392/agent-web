package com.example.agentweb.domain.refinery;

import lombok.Getter;
import java.time.Instant;

/**
 * below-threshold(评分 &lt; score-threshold) 被丢弃的会话留痕.
 *
 * <p>与 {@link RagChunk} 平行但更轻: 无 embedding、不参与召回, 只为管理台"已丢弃(低分)"
 * 展示与阈值校准而存在. 由 {@code RefineryAppServiceImpl.ingestCore} 在丢弃点落库,
 * source 来自 {@link ConversationView}, title/conclusion/score/ttl 来自 {@link RefinedContent}.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-04
 */
public final class DiscardedRefineRecord {

    /** 默认丢弃原因: 评分低于阈值. */
    public static final String REASON_BELOW_THRESHOLD = "score below threshold";

    @Getter
    private final String id;
    @Getter
    private final SourceType sourceType;
    @Getter
    private final String sourceSessionId;
    @Getter
    private final String title;
    @Getter
    private final String conclusion;
    @Getter
    private final TtlCategory ttlCategory;
    @Getter
    private final double score;
    @Getter
    private final double threshold;
    @Getter
    private final String agentType;
    @Getter
    private final String env;
    @Getter
    private final Instant createdAt;
    @Getter
    private final String reason;

    private DiscardedRefineRecord(Builder b) {
        if (b.id == null || b.id.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (b.sourceType == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }
        if (b.sourceSessionId == null || b.sourceSessionId.isEmpty()) {
            throw new IllegalArgumentException("sourceSessionId must not be blank");
        }
        if (b.title == null || b.title.isEmpty()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (b.createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        this.id = b.id;
        this.sourceType = b.sourceType;
        this.sourceSessionId = b.sourceSessionId;
        this.title = b.title;
        this.conclusion = b.conclusion;
        this.ttlCategory = b.ttlCategory;
        this.score = b.score;
        this.threshold = b.threshold;
        this.agentType = b.agentType;
        this.env = b.env;
        this.createdAt = b.createdAt;
        this.reason = b.reason == null ? REASON_BELOW_THRESHOLD : b.reason;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 链式构造器, 12 个字段直接全参构造可读性差, 故用 builder. */
    public static final class Builder {
        private String id;
        private SourceType sourceType;
        private String sourceSessionId;
        private String title;
        private String conclusion;
        private TtlCategory ttlCategory;
        private double score;
        private double threshold;
        private String agentType;
        private String env;
        private Instant createdAt;
        private String reason;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder sourceType(SourceType sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder sourceSessionId(String sourceSessionId) {
            this.sourceSessionId = sourceSessionId;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder conclusion(String conclusion) {
            this.conclusion = conclusion;
            return this;
        }

        public Builder ttlCategory(TtlCategory ttlCategory) {
            this.ttlCategory = ttlCategory;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder threshold(double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder env(String env) {
            this.env = env;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public DiscardedRefineRecord build() {
            return new DiscardedRefineRecord(this);
        }
    }
}
