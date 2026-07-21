package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

/**
 * Top-score sample for threshold tuning charts.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallScorePoint {

    private String attemptId;
    private String status;
    private Double topVectorScore;
    private Double topFinalScore;
    private Integer belowVectorFloor;
    private Integer filteredCount;
    private Integer badVectorCount;
    private Integer rankedCount;
    private Long createdAt;
}
