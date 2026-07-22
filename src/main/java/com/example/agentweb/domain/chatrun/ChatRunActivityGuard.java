package com.example.agentweb.domain.chatrun;

/**
 * Guards destructive ChatSession mutations while a ChatRun is active.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@FunctionalInterface
public interface ChatRunActivityGuard {

    void requireInactive(String sessionId);

    static ChatRunActivityGuard permissive() {
        return sessionId -> {
        };
    }
}
