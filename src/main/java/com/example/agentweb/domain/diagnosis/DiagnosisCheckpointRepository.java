package com.example.agentweb.domain.diagnosis;

import java.util.Optional;

/**
 * Persistence port for immutable native diagnosis checkpoints.
 *
 * @author alex
 * @since 2026-07-29
 */
public interface DiagnosisCheckpointRepository {

    void save(DiagnosisCheckpoint checkpoint);

    Optional<DiagnosisCheckpoint> findLatestValidBefore(String sessionId, long currentUserMessageId);

    void deleteCrossingBoundary(String sessionId, long fromMessageId);

    void deleteBySessionId(String sessionId);
}
