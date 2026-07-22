package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import lombok.Getter;

import java.time.Instant;

/**
 * Persisted, immutable run stream event.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunEvent {

    private final ChatRunId runId;
    private final long seq;
    private final String eventType;
    private final String payload;
    private final int payloadSize;
    private final Instant createdAt;

    public ChatRunEvent(ChatRunId runId, long seq, String eventType, String payload,
                        int payloadSize, Instant createdAt) {
        this.runId = runId;
        this.seq = seq;
        this.eventType = eventType;
        this.payload = payload;
        this.payloadSize = payloadSize;
        this.createdAt = createdAt;
    }
}
