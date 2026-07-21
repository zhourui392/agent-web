package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregated recall observability metrics.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallMetricsSummary {

    private long attemptCount;
    private long executedCount;
    private long hitCount;
    private long noHitCount;
    private long errorCount;
    private long skippedCount;
    private long pendingCount;
    private Double serviceAvailabilityRate;
    private Double qualityHitRate;
    private Double userVisibleHitRate;
    private Double noHitRate;
    private Double errorRate;
    private Double avgHitCount;
    private Double avgLatencyMs;
    private Map<String, Long> byStatus = new LinkedHashMap<>();
    private Map<String, Long> byEmbeddingModel = new LinkedHashMap<>();
    private Map<String, Long> byEnv = new LinkedHashMap<>();
    private Map<String, Long> bySourceType = new LinkedHashMap<>();
    private Map<String, Long> byTier = new LinkedHashMap<>();
    private List<RecallBucketMetric> envBuckets = new ArrayList<>();
    private List<RecallBucketMetric> sourceTypeBuckets = new ArrayList<>();
    private List<RecallBucketMetric> tierBuckets = new ArrayList<>();
    private List<RecallBucketMetric> embeddingModelBuckets = new ArrayList<>();
    private List<RecallScorePoint> scoreSamples = new ArrayList<>();
}
