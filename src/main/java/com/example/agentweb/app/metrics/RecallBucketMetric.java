package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

/**
 * Recall quality metric for one grouping bucket.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallBucketMetric {

    private String key;
    private long hitCount;
    private long noHitCount;
    private long errorCount;
    private long executedCount;
    private Double qualityHitRate;
    private Double userVisibleHitRate;
}
