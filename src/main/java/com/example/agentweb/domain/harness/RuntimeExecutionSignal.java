package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;

/**
 * Runtime Adapter 发回 Domain 的非敏感、可幂等归一化信号。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimeExecutionSignal {

    private final RuntimeExecutionSignalType type;
    private final long sequence;
    private final String runtimeVersion;
    private final String runtimeHandle;
    private final Integer exitCode;
    private final String reason;
    private final String evidenceReference;
    private final Boolean temporaryConfigCleaned;
    private final Instant occurredAt;

    private RuntimeExecutionSignal(RuntimeExecutionSignalType type, long sequence,
                                   String runtimeVersion, String runtimeHandle, Integer exitCode,
                                   String reason, String evidenceReference,
                                   Boolean temporaryConfigCleaned, Instant occurredAt) {
        if (type == null || sequence < 1L) {
            throw new IllegalArgumentException("runtime signal type and positive sequence are required");
        }
        this.type = type;
        this.sequence = sequence;
        this.runtimeVersion = runtimeVersion;
        this.runtimeHandle = runtimeHandle;
        this.exitCode = exitCode;
        this.reason = reason;
        this.evidenceReference = evidenceReference;
        this.temporaryConfigCleaned = temporaryConfigCleaned;
        this.occurredAt = DomainText.requireTime(occurredAt, "runtime signal time");
    }

    public static RuntimeExecutionSignal started(long sequence, String runtimeVersion,
                                                 String runtimeHandle, Instant occurredAt) {
        return new RuntimeExecutionSignal(RuntimeExecutionSignalType.STARTED, sequence,
                DomainText.require(runtimeVersion, "runtime signal version", 160),
                DomainText.require(runtimeHandle, "runtime signal handle", 240),
                null, null, null, null, occurredAt);
    }

    public static RuntimeExecutionSignal output(long sequence, String evidenceReference,
                                                Instant occurredAt) {
        return new RuntimeExecutionSignal(RuntimeExecutionSignalType.OUTPUT, sequence,
                null, null, null, null, evidenceReference, null, occurredAt);
    }

    public static RuntimeExecutionSignal succeeded(long sequence, int exitCode,
                                                   String evidenceReference, boolean cleaned,
                                                   Instant occurredAt) {
        return terminal(RuntimeExecutionSignalType.SUCCEEDED, sequence, exitCode,
                "runtime succeeded", evidenceReference, cleaned, occurredAt);
    }

    public static RuntimeExecutionSignal failed(long sequence, Integer exitCode, String reason,
                                                boolean cleaned, Instant occurredAt) {
        return failed(sequence, exitCode, reason, null, cleaned, occurredAt);
    }

    public static RuntimeExecutionSignal failed(long sequence, Integer exitCode, String reason,
                                                String evidenceReference, boolean cleaned,
                                                Instant occurredAt) {
        return terminal(RuntimeExecutionSignalType.FAILED, sequence, exitCode, reason,
                evidenceReference, cleaned, occurredAt);
    }

    public static RuntimeExecutionSignal timedOut(long sequence, String reason, boolean cleaned,
                                                  Instant occurredAt) {
        return timedOut(sequence, reason, null, cleaned, occurredAt);
    }

    public static RuntimeExecutionSignal timedOut(long sequence, String reason,
                                                  String evidenceReference, boolean cleaned,
                                                  Instant occurredAt) {
        return terminal(RuntimeExecutionSignalType.TIMED_OUT, sequence, null, reason,
                evidenceReference, cleaned, occurredAt);
    }

    public static RuntimeExecutionSignal cancelled(long sequence, Integer exitCode, String reason,
                                                   boolean cleaned, Instant occurredAt) {
        return cancelled(sequence, exitCode, reason, null, cleaned, occurredAt);
    }

    public static RuntimeExecutionSignal cancelled(long sequence, Integer exitCode, String reason,
                                                   String evidenceReference, boolean cleaned,
                                                   Instant occurredAt) {
        return terminal(RuntimeExecutionSignalType.CANCELLED, sequence, exitCode, reason,
                evidenceReference, cleaned, occurredAt);
    }

    public static RuntimeExecutionSignal lost(long sequence, String reason, Instant occurredAt) {
        return terminal(RuntimeExecutionSignalType.LOST, sequence, null, reason,
                null, false, occurredAt);
    }

    private static RuntimeExecutionSignal terminal(RuntimeExecutionSignalType type, long sequence,
                                                    Integer exitCode, String reason,
                                                    String evidenceReference, boolean cleaned,
                                                    Instant occurredAt) {
        return new RuntimeExecutionSignal(type, sequence, null, null, exitCode,
                DomainText.require(reason, "runtime termination reason", 1000),
                evidenceReference, Boolean.valueOf(cleaned), occurredAt);
    }
}
