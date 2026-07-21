package com.example.agentweb.app.refinery;

import java.util.Optional;

/**
 * Best-effort write-side port for chat recall observability projection.
 *
 * @author codex
 * @since 2026-06-12
 */
public interface RecallObservationRecorder {

    Optional<String> tryCreateStart(RecallObservationStart start);

    void tryRecordTrace(String attemptId, RecallTrace trace);

    void tryAttachAssistantMessage(String attemptId, long assistantMessageId);

    void tryDeleteBySessionId(String sessionId);

    void tryDeleteByMessageRange(String sessionId, long fromId);
}
