package com.example.agentweb.app.agentrun.port;

/**
 * How prior conversation history reaches a runtime.
 *
 * @author alex
 * @since 2026-07-29
 */
public enum HistoryDeliveryMode {
    PROMPT_PREFIX,
    PROVIDER_RESUME,
    TYPED
}
