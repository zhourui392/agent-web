package com.example.agentweb.domain.refinery;

import lombok.Getter;
import com.example.agentweb.domain.shared.AgentType;

import java.time.Instant;
import java.util.Objects;

/**
 * refinery 的核心聚合根: 一条可向量召回的会话经验记录.
 *
 * <p>不变量:</p>
 * <ul>
 *   <li>{@code id} / {@code sourceSessionId} / {@code agentType} / {@code content} /
 *       {@code ttlCategory} / {@code createdAt} / {@code embeddingModel} / {@code embedding} 必填</li>
 *   <li>{@code score} 必须在 [0, 1] 之间</li>
 *   <li>{@code embedding} 长度 > 0</li>
 *   <li>{@code expiresAt} / {@code archivedAt} / {@code sourceMsgRange} 可空</li>
 * </ul>
 *
 * <p>状态迁移: 仅 {@link #archive(Instant)} 可改变状态 (NULL archived_at → 设置归档时间);
 * 其他字段一经创建不可变.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public final class RagChunk {

    @Getter
    private final String id;
    @Getter
    private final String sourceSessionId;
    @Getter
    private final String sourceMsgRange;
    @Getter
    private final AgentType agentType;
    @Getter
    private final RefinedContent content;
    @Getter
    private final double score;
    @Getter
    private final TtlCategory ttlCategory;
    @Getter
    private final Instant createdAt;
    @Getter
    private final Instant expiresAt;
    @Getter
    private final String embeddingModel;
    @Getter
    private final float[] embedding;
    @Getter
    private final SourceType sourceType;
    @Getter
    private final String env;
    /** 正文原文的 workingDir 相对路径 (如 issue-log 文件); 可空, 空则召回时物化临时文件. */
    @Getter
    private final String detailPath;
    @Getter
    private Instant archivedAt;
    @Getter
    private TrustTier tier;

    private RagChunk(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.sourceSessionId = Objects.requireNonNull(builder.sourceSessionId, "sourceSessionId");
        this.sourceMsgRange = builder.sourceMsgRange;
        this.agentType = Objects.requireNonNull(builder.agentType, "agentType");
        this.content = Objects.requireNonNull(builder.content, "content");
        this.score = requireScoreInRange(builder.score);
        this.ttlCategory = Objects.requireNonNull(builder.ttlCategory, "ttlCategory");
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt");
        this.expiresAt = builder.expiresAt;
        this.embeddingModel = Objects.requireNonNull(builder.embeddingModel, "embeddingModel");
        this.embedding = requireNonEmptyEmbedding(builder.embedding);
        this.archivedAt = builder.archivedAt;
        // Phase 1.3: 子域演进字段, 历史数据走默认值兼容
        this.sourceType = builder.sourceType == null ? SourceType.CHAT : builder.sourceType;
        this.tier = builder.tier == null ? TrustTier.EXPLORATORY : builder.tier;
        this.env = builder.env == null ? ConversationView.DEFAULT_ENV : builder.env;
        this.detailPath = builder.detailPath;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 归档本 chunk. 一旦归档不可撤销 (再次调用抛 IllegalStateException).
     * 用于 TTL 过期或人工标记下线; 软删, 数据保留, 召回路径默认过滤掉.
     */
    public void archive(Instant when) {
        Objects.requireNonNull(when, "when");
        if (this.archivedAt != null) {
            throw new IllegalStateException("chunk already archived at " + this.archivedAt);
        }
        this.archivedAt = when;
    }

    /** 返回内部 float[] 引用; 调用方不应修改, 否则破坏聚合不变量. */

    /**
     * 升级 trust tier (例如收到正面反馈后 PENDING → VERIFIED).
     * 仅允许向上升级, 降级走 {@link #archive(Instant)} 路径.
     */
    public void upgradeTier(TrustTier newTier) {
        Objects.requireNonNull(newTier, "newTier");
        if (newTier.ordinal() > this.tier.ordinal()) {
            throw new IllegalArgumentException(
                    "tier may only be upgraded towards VERIFIED, was " + this.tier + " → " + newTier);
        }
        this.tier = newTier;
    }

    private static double requireScoreInRange(double score) {
        if (score < 0d || score > 1d || Double.isNaN(score)) {
            throw new IllegalArgumentException("score must be in [0, 1]: " + score);
        }
        return score;
    }

    private static float[] requireNonEmptyEmbedding(float[] embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (embedding.length == 0) {
            throw new IllegalArgumentException("embedding cannot be empty");
        }
        return embedding;
    }

    public static final class Builder {
        private String id;
        private String sourceSessionId;
        private String sourceMsgRange;
        private AgentType agentType;
        private RefinedContent content;
        private double score;
        private TtlCategory ttlCategory;
        private Instant createdAt;
        private Instant expiresAt;
        private String embeddingModel;
        private float[] embedding;
        private Instant archivedAt;
        private SourceType sourceType;
        private TrustTier tier;
        private String env;
        private String detailPath;

        private Builder() {
        }

        public Builder detailPath(String detailPath) {
            this.detailPath = detailPath;
            return this;
        }

        public Builder sourceType(SourceType sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder tier(TrustTier tier) {
            this.tier = tier;
            return this;
        }

        public Builder env(String env) {
            this.env = env;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder sourceSessionId(String sourceSessionId) {
            this.sourceSessionId = sourceSessionId;
            return this;
        }

        public Builder sourceMsgRange(String sourceMsgRange) {
            this.sourceMsgRange = sourceMsgRange;
            return this;
        }

        public Builder agentType(AgentType agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder content(RefinedContent content) {
            this.content = content;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder ttlCategory(TtlCategory ttlCategory) {
            this.ttlCategory = ttlCategory;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder embeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public Builder embedding(float[] embedding) {
            this.embedding = embedding;
            return this;
        }

        public Builder archivedAt(Instant archivedAt) {
            this.archivedAt = archivedAt;
            return this;
        }

        public RagChunk build() {
            return new RagChunk(this);
        }
    }
}
