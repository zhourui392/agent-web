package com.example.agentweb.app.metrics;

import lombok.Getter;

/**
 * Filter object for recall observability metrics queries.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
public class RecallMetricsFilter {

    private final String status;
    private final String sessionId;
    private final Long from;
    private final Long to;
    private final String embeddingModel;
    private final String env;
    private final String sourceType;
    private final String tier;

    public RecallMetricsFilter(String status, String sessionId, Long from, Long to,
                               String embeddingModel, String env, String sourceType, String tier) {
        this.status = trimToNull(status);
        this.sessionId = trimToNull(sessionId);
        this.from = from;
        this.to = to;
        this.embeddingModel = trimToNull(embeddingModel);
        this.env = trimToNull(env);
        this.sourceType = trimToNull(sourceType);
        this.tier = trimToNull(tier);
    }

    public static RecallMetricsFilter timeRange(Long from, Long to) {
        return new RecallMetricsFilter(null, null, from, to, null, null, null, null);
    }

    public boolean hasHitFilter() {
        return sourceType != null || tier != null;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}
