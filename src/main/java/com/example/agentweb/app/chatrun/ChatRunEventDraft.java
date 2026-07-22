package com.example.agentweb.app.chatrun;

import lombok.Getter;

/**
 * Unsequenced public event payload waiting to be appended to one run stream.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunEventDraft {

    private final String eventType;
    private final String payload;

    public ChatRunEventDraft(String eventType, String payload) {
        if (eventType == null || eventType.trim().isEmpty()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("event payload must not be null");
        }
        this.eventType = eventType.trim();
        this.payload = payload;
    }
}
