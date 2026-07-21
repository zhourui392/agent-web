package com.example.agentweb.app.refinery;

import lombok.Getter;

/**
 * Request-thread snapshot used to create an initial recall observation attempt.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
public final class RecallObservationStart {

    private final String sessionId;
    private final long userMessageId;
    private final String query;
    private final boolean recallEnabled;
    private final String env;
    private final RecallStatus status;
    private final String skipReason;

    public RecallObservationStart(String sessionId, long userMessageId, String query,
                                  boolean recallEnabled, String env,
                                  RecallStatus status, String skipReason) {
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
        this.query = query;
        this.recallEnabled = recallEnabled;
        this.env = env;
        this.status = status;
        this.skipReason = skipReason;
    }
}
