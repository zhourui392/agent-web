package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;

/**
 * RuntimeExecution 的非敏感追加事件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimeExecutionEvent {

    private final String executionId;
    private final long sequence;
    private final RuntimeExecutionSignalType type;
    private final String summary;
    private final String evidenceReference;
    private final Instant occurredAt;

    public RuntimeExecutionEvent(String executionId, long sequence,
                                 RuntimeExecutionSignalType type, String summary,
                                 String evidenceReference, Instant occurredAt) {
        this.executionId = DomainText.require(executionId, "runtime event execution id", 128);
        if (sequence < 1L || type == null) {
            throw new IllegalArgumentException("runtime event sequence and type are required");
        }
        this.sequence = sequence;
        this.type = type;
        this.summary = summary == null ? null : DomainText.require(summary, "runtime event summary", 4000);
        this.evidenceReference = evidenceReference;
        this.occurredAt = DomainText.requireTime(occurredAt, "runtime event time");
    }
}
