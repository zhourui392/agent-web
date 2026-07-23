package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;

/**
 * 可持久化的最小 Harness 审计事件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessEvent {

    private final long sequence;
    private final String eventType;
    private final HarnessStage stage;
    private final String actor;
    private final String detail;
    private final Instant occurredAt;

    public HarnessEvent(long sequence, String eventType, HarnessStage stage,
                        String actor, String detail, Instant occurredAt) {
        if (sequence < 1L) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
        this.sequence = sequence;
        this.eventType = DomainText.require(eventType, "event type");
        this.stage = stage;
        this.actor = DomainText.require(actor, "event actor", 128);
        this.detail = detail == null ? null : detail.trim();
        this.occurredAt = DomainText.requireTime(occurredAt, "event occurred time");
    }
}
