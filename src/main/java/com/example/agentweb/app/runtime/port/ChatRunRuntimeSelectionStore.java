package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.chatrun.ChatRunId;

import java.util.Optional;

/** Persists the non-secret RuntimeSelection frozen for one ChatRun.
 *
 * @author alex
 * @since 2026-08-07
 */
public interface ChatRunRuntimeSelectionStore {

    void save(ChatRunId runId, RuntimeSelection selection);

    Optional<RuntimeSelection> find(ChatRunId runId);
}
