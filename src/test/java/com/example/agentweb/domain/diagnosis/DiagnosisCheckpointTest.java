package com.example.agentweb.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author alex
 * @since 2026-07-29
 */
class DiagnosisCheckpointTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");

    @Test
    void record_shouldPreserveImmutableSuccessfulMessageBoundary() {
        DiagnosisCheckpoint checkpoint = DiagnosisCheckpoint.record(
                "run-1", "session-1", 11L, 12L, "{\"phase\":\"evidence\"}",
                "v1", 101L, 22L, 7L, CREATED_AT);

        assertEquals("run-1", checkpoint.getRunId());
        assertEquals("session-1", checkpoint.getSessionId());
        assertEquals(11L, checkpoint.getUserMessageId());
        assertEquals(12L, checkpoint.getAssistantMessageId());
        assertEquals("{\"phase\":\"evidence\"}", checkpoint.getStateSnapshot());
        assertEquals("v1", checkpoint.getSnapshotSchemaVersion());
        assertEquals(101L, checkpoint.getInputTokens());
        assertEquals(22L, checkpoint.getOutputTokens());
        assertEquals(7L, checkpoint.getCacheReadInputTokens());
        assertEquals(CREATED_AT, checkpoint.getCreatedAt());
    }

    @Test
    void record_shouldRejectInvalidBoundaryAndUsage() {
        assertThrows(IllegalArgumentException.class, () -> DiagnosisCheckpoint.record(
                "run-1", "session-1", 0L, 12L, "state", "", 0L, 0L, 0L, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> DiagnosisCheckpoint.record(
                "run-1", "session-1", 11L, 10L, "state", "", 0L, 0L, 0L, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> DiagnosisCheckpoint.record(
                "run-1", "session-1", 11L, 12L, " ", "", 0L, 0L, 0L, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> DiagnosisCheckpoint.record(
                "run-1", "session-1", 11L, 12L, "state", "", -1L, 0L, 0L, CREATED_AT));
    }
}
