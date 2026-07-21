package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

/**
 * Chunk-level recall usage statistics.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallChunkStat {

    private String chunkId;
    private long recalledTimes;
    private Double avgVectorScore;
    private Double avgFinalScore;
    private String title;
    private String sourceType;
    private String tier;
    private Long lastRecalledAt;
}
