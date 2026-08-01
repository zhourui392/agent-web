package com.example.agentweb.app.runtime.port;

/**
 * 历史消息到达 Runtime 的明确方式。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum HistoryDelivery {
    PROMPT_PREFIX,
    PROVIDER_RESUME,
    TYPED
}
