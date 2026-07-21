package com.example.agentweb.app.refinery;

import lombok.Getter;

import java.time.Instant;

/**
 * One scored hit in a recall trace.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
public final class ScoredRecallHit {

    private final String chunkId;
    private final int rankNo;
    private final String title;
    private final String conclusion;
    private final String sourceSessionId;
    private final String sourceMsgRange;
    private final double finalScore;
    private final double vectorScore;
    private final double signalScore;
    private final double timeScore;
    private final String embeddingModel;
    private final String sourceType;
    private final String tier;
    private final String env;
    private final double chunkScore;
    private final Instant chunkCreatedAt;

    public ScoredRecallHit(String chunkId, int rankNo, String title, String conclusion,
                           String sourceSessionId, String sourceMsgRange,
                           double finalScore, double vectorScore, double signalScore, double timeScore,
                           String embeddingModel, String sourceType, String tier, String env,
                           double chunkScore, Instant chunkCreatedAt) {
        this.chunkId = chunkId;
        this.rankNo = rankNo;
        this.title = title;
        this.conclusion = conclusion;
        this.sourceSessionId = sourceSessionId;
        this.sourceMsgRange = sourceMsgRange;
        this.finalScore = finalScore;
        this.vectorScore = vectorScore;
        this.signalScore = signalScore;
        this.timeScore = timeScore;
        this.embeddingModel = embeddingModel;
        this.sourceType = sourceType;
        this.tier = tier;
        this.env = env;
        this.chunkScore = chunkScore;
        this.chunkCreatedAt = chunkCreatedAt;
    }

    public RecallHit toPublicHit() {
        return new RecallHit(title, conclusion, sourceSessionId);
    }
}
