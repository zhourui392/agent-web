package com.example.agentweb.app.agentrun.port;

import java.util.Objects;

/**
 * Opaque runtime-owned state to persist on successful ChatRun completion.
 *
 * @author alex
 * @since 2026-07-29
 */
public record AgentStateCheckpointPayload(String stateSnapshot, String schemaVersion) {

    public AgentStateCheckpointPayload {
        Objects.requireNonNull(stateSnapshot, "stateSnapshot");
        schemaVersion = schemaVersion == null ? "" : schemaVersion;
    }
}
