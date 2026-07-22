package com.example.agentweb.app.chatrun;

import lombok.Getter;

/**
 * One bounded retention pass result.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunRetentionResult {

    private final int deletedEvents;
    private final int deletedRuns;

    public ChatRunRetentionResult(int deletedEvents, int deletedRuns) {
        this.deletedEvents = deletedEvents;
        this.deletedRuns = deletedRuns;
    }
}
