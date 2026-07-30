package com.example.agentweb.app.agentrun.port;

import java.util.Objects;

/**
 * Provider-neutral persisted conversation message.
 *
 * @author alex
 * @since 2026-07-29
 */
public record AgentHistoryMessage(String role, String content) {

    public AgentHistoryMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }
}
