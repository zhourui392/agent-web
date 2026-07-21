package com.example.agentweb.app.refinery;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Execution facts for one chat recall attempt.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
public final class RecallTrace {

    private final RecallStatus status;
    private final String skipReason;
    private final String query;
    private final String augmentedMessage;
    private final List<ScoredRecallHit> hits;
    private final RecallStats stats;
    private final String errorType;
    private final String errorMessage;
    private final long latencyMs;

    private RecallTrace(RecallStatus status, String skipReason, String query, String augmentedMessage,
                        List<ScoredRecallHit> hits, RecallStats stats,
                        String errorType, String errorMessage, long latencyMs) {
        this.status = status;
        this.skipReason = skipReason;
        this.query = query;
        this.augmentedMessage = augmentedMessage;
        this.hits = hits == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(hits));
        this.stats = stats;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.latencyMs = latencyMs;
    }

    public static RecallTrace skipped(String query, String originalMessage, String skipReason, long latencyMs) {
        return new RecallTrace(RecallStatus.SKIPPED, skipReason, query, originalMessage,
                Collections.emptyList(), null, null, null, latencyMs);
    }

    public static RecallTrace noHit(String query, String originalMessage, RecallStats stats, long latencyMs) {
        return new RecallTrace(RecallStatus.NO_HIT, null, query, originalMessage,
                Collections.emptyList(), stats, null, null, latencyMs);
    }

    public static RecallTrace hit(String query, String augmentedMessage,
                                  List<ScoredRecallHit> hits, RecallStats stats, long latencyMs) {
        return new RecallTrace(RecallStatus.HIT, null, query, augmentedMessage,
                hits, stats, null, null, latencyMs);
    }

    public static RecallTrace error(String query, String originalMessage,
                                    String errorType, String errorMessage, long latencyMs) {
        return new RecallTrace(RecallStatus.ERROR, null, query, originalMessage,
                Collections.emptyList(), null, errorType, errorMessage, latencyMs);
    }

    public RecallOutcome toOutcome() {
        if (status != RecallStatus.HIT || hits.isEmpty()) {
            return RecallOutcome.notRecalled(augmentedMessage);
        }
        List<RecallHit> publicHits = new ArrayList<>(hits.size());
        for (ScoredRecallHit hit : hits) {
            publicHits.add(hit.toPublicHit());
        }
        return RecallOutcome.recalled(augmentedMessage, query, publicHits);
    }
}
