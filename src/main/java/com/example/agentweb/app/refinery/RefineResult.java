package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.TtlCategory;

/**
 * LLM 评分 + 精炼结果的 VO. 由 {@link ConversationRefinery#refine} 产出,
 * AppService 决定是否入库 (score &lt; threshold 直接丢).
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public final class RefineResult {

    private final double score;
    private final TtlCategory ttlCategory;
    private final RefinedContent content;

    public RefineResult(double score, TtlCategory ttlCategory, RefinedContent content) {
        if (score < 0d || score > 1d) {
            throw new IllegalArgumentException("score out of range [0,1]: " + score);
        }
        if (ttlCategory == null) {
            throw new IllegalArgumentException("ttlCategory required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content required");
        }
        this.score = score;
        this.ttlCategory = ttlCategory;
        this.content = content;
    }

    public double getScore() {
        return score;
    }

    public TtlCategory getTtlCategory() {
        return ttlCategory;
    }

    public RefinedContent getContent() {
        return content;
    }
}
