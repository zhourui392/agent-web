package com.example.agentweb.domain.chatrun;

import lombok.Getter;

/**
 * Contiguous sequence range allocated to one persisted event batch.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class EventSequenceRange {

    private final long startInclusive;
    private final long endInclusive;

    public EventSequenceRange(long startInclusive, long endInclusive) {
        if (startInclusive <= 0L || endInclusive < startInclusive) {
            throw new IllegalArgumentException("invalid event sequence range");
        }
        this.startInclusive = startInclusive;
        this.endInclusive = endInclusive;
    }
}
