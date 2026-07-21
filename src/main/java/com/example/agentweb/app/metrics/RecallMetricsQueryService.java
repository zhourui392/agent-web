package com.example.agentweb.app.metrics;

import java.util.List;

/**
 * Read-side query service for chat recall observability projection.
 *
 * @author codex
 * @since 2026-06-12
 */
public interface RecallMetricsQueryService {

    RecallMetricsSummary summary(RecallMetricsFilter filter);

    RecallAttemptPage listAttempts(int page, int size, RecallMetricsFilter filter);

    RecallAttemptDetail detail(String id);

    RecallAttemptDetail detailByMessageId(long messageId);

    List<RecallChunkStat> topChunks(int limit, RecallMetricsFilter filter);
}
