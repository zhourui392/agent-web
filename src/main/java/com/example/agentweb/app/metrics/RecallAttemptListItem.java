package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

/**
 * Recall attempt list row.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallAttemptListItem {

    private String id;
    private String sessionId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String querySummary;
    private boolean recallEnabled;
    private String env;
    private String status;
    private String skipReason;
    private int hitCount;
    private Integer topK;
    private Integer activeCount;
    private Integer filteredCount;
    private Integer belowVectorFloor;
    private Integer badVectorCount;
    private Integer rankedCount;
    private Double topVectorScore;
    private Double topFinalScore;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Long latencyMs;
    private String errorType;
    private Long createdAt;
    private Long updatedAt;
}
