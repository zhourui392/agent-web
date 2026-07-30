package com.example.agentweb.domain.diagnosis;

import lombok.Getter;

import java.time.Instant;

/**
 * Immutable diagnosis state produced at one successfully persisted chat message boundary.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public final class DiagnosisCheckpoint {

    private final String runId;
    private final String sessionId;
    private final long userMessageId;
    private final long assistantMessageId;
    private final String stateSnapshot;
    private final String snapshotSchemaVersion;
    private final long inputTokens;
    private final long outputTokens;
    private final long cacheReadInputTokens;
    private final Instant createdAt;

    private DiagnosisCheckpoint(String runId, String sessionId, long userMessageId,
                                long assistantMessageId, String stateSnapshot,
                                String snapshotSchemaVersion, long inputTokens,
                                long outputTokens, long cacheReadInputTokens, Instant createdAt) {
        this.runId = requireText(runId, "run id");
        this.sessionId = requireText(sessionId, "session id");
        if (userMessageId <= 0L) {
            throw new IllegalArgumentException("user message id must be positive");
        }
        if (assistantMessageId <= userMessageId) {
            throw new IllegalArgumentException("assistant message id must follow user message id");
        }
        this.userMessageId = userMessageId;
        this.assistantMessageId = assistantMessageId;
        this.stateSnapshot = requireText(stateSnapshot, "state snapshot");
        this.snapshotSchemaVersion = snapshotSchemaVersion == null
                ? "" : snapshotSchemaVersion.trim();
        requireNonNegative(inputTokens, "input tokens");
        requireNonNegative(outputTokens, "output tokens");
        requireNonNegative(cacheReadInputTokens, "cache read input tokens");
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
        if (createdAt == null) {
            throw new IllegalArgumentException("created time must not be null");
        }
        this.createdAt = createdAt;
    }

    public static DiagnosisCheckpoint record(String runId, String sessionId,
                                             long userMessageId, long assistantMessageId,
                                             String stateSnapshot, String snapshotSchemaVersion,
                                             long inputTokens, long outputTokens,
                                             long cacheReadInputTokens, Instant createdAt) {
        return new DiagnosisCheckpoint(runId, sessionId, userMessageId, assistantMessageId,
                stateSnapshot, snapshotSchemaVersion, inputTokens, outputTokens,
                cacheReadInputTokens, createdAt);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
