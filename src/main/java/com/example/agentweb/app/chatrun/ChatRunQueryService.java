package com.example.agentweb.app.chatrun;

import java.util.List;
import java.util.Optional;

/**
 * CQRS read-side port for active-run projections.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunQueryService {

    List<ActiveChatRunView> findActiveForCurrentUser();

    long countActiveRuns();

    List<String> findActiveRunIds();

    Optional<ChatRunExecutionContext> findExecutionContext(String runId);
}
