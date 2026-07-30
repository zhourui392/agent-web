package com.example.agentweb.app.agentrun.port;

/**
 * Provider-neutral token usage for one physical agent run.
 *
 * @author alex
 * @since 2026-07-29
 */
public record AgentUsage(long inputTokens, long outputTokens, long cacheReadInputTokens) {

    public static AgentUsage zero() {
        return new AgentUsage(0L, 0L, 0L);
    }
}
