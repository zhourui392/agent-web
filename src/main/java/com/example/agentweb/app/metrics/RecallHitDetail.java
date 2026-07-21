package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

/**
 * Recall hit detail row.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallHitDetail {

    private int rankNo;
    private String chunkId;
    private String sourceSessionId;
    private String sourceMsgRange;
    private String title;
    private String conclusion;
    private Double finalScore;
    private Double vectorScore;
    private Double signalScore;
    private Double timeScore;
    private String embeddingModel;
    private String sourceType;
    private String tier;
    private String env;
    private Double chunkScore;
    private Long chunkCreatedAt;
    private Long createdAt;
}
