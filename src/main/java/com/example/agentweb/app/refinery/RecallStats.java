package com.example.agentweb.app.refinery;

import lombok.Getter;

/**
 * Snapshot of recall execution counters and strategy parameters.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
public final class RecallStats {

    private final int activeCount;
    private final int filteredCount;
    private final int belowVectorFloor;
    private final int rankedCount;
    private final int badVectorCount;
    private final Double topVectorScore;
    private final Double topFinalScore;
    private final Integer topK;
    private final boolean includeArchived;
    private final boolean crossSourceEnabled;
    private final double halfLifeDays;
    private final double minVectorScore;
    private final double minScore;
    private final double minScoreRatio;
    private final double vectorWeight;
    private final double signalWeight;
    private final double timeDecayWeight;
    private final String embeddingModel;
    private final int embeddingDimension;

    public RecallStats(int activeCount, int filteredCount, int belowVectorFloor, int rankedCount,
                       int badVectorCount, Double topVectorScore, Double topFinalScore, Integer topK,
                       boolean includeArchived, boolean crossSourceEnabled, double halfLifeDays,
                       double minVectorScore, double minScore, double minScoreRatio,
                       double vectorWeight, double signalWeight, double timeDecayWeight,
                       String embeddingModel, int embeddingDimension) {
        this.activeCount = activeCount;
        this.filteredCount = filteredCount;
        this.belowVectorFloor = belowVectorFloor;
        this.rankedCount = rankedCount;
        this.badVectorCount = badVectorCount;
        this.topVectorScore = topVectorScore;
        this.topFinalScore = topFinalScore;
        this.topK = topK;
        this.includeArchived = includeArchived;
        this.crossSourceEnabled = crossSourceEnabled;
        this.halfLifeDays = halfLifeDays;
        this.minVectorScore = minVectorScore;
        this.minScore = minScore;
        this.minScoreRatio = minScoreRatio;
        this.vectorWeight = vectorWeight;
        this.signalWeight = signalWeight;
        this.timeDecayWeight = timeDecayWeight;
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
    }
}
