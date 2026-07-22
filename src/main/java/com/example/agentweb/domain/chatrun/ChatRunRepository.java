package com.example.agentweb.domain.chatrun;

import java.util.Optional;

/**
 * Write-side repository for the ChatRun aggregate lifecycle.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunRepository {

    void add(ChatRun run);

    void update(ChatRun run);

    Optional<ChatRun> findById(ChatRunId id);

    Optional<ChatRun> findBySessionAndIdempotencyKey(String sessionId, String idempotencyKey);

    Optional<ChatRun> findActiveBySessionId(String sessionId);
}
