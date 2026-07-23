package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;

/**
 * 一次不可覆盖的 Stage 执行尝试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class StageAttempt {

    private final int number;
    private final String idempotencyKey;
    private final Instant startedAt;
    private StageAttemptStatus status;
    private Instant finishedAt;
    private String failureReason;

    private StageAttempt(int number, String idempotencyKey, Instant startedAt,
                         StageAttemptStatus status, Instant finishedAt, String failureReason) {
        if (number < 1) {
            throw new IllegalArgumentException("attempt number must be positive");
        }
        this.number = number;
        this.idempotencyKey = DomainText.require(idempotencyKey, "attempt idempotency key", 128);
        this.startedAt = DomainText.requireTime(startedAt, "attempt started time");
        if (status == null) {
            throw new IllegalArgumentException("attempt status must not be null");
        }
        this.status = status;
        this.finishedAt = finishedAt;
        this.failureReason = failureReason;
        validateRestoredState();
    }

    public static StageAttempt start(int number, String idempotencyKey, Instant now) {
        return new StageAttempt(number, idempotencyKey, now,
                StageAttemptStatus.RUNNING, null, null);
    }

    public static StageAttempt restore(int number, String idempotencyKey, Instant startedAt,
                                       StageAttemptStatus status, Instant finishedAt,
                                       String failureReason) {
        return new StageAttempt(number, idempotencyKey, startedAt, status, finishedAt, failureReason);
    }

    void waitForApproval() {
        requireStatus(StageAttemptStatus.RUNNING);
        status = StageAttemptStatus.WAITING_APPROVAL;
    }

    void resumeAfterRejection() {
        requireStatus(StageAttemptStatus.WAITING_APPROVAL);
        status = StageAttemptStatus.RUNNING;
    }

    void pass(Instant now) {
        requireStatus(StageAttemptStatus.WAITING_APPROVAL);
        status = StageAttemptStatus.PASSED;
        finishedAt = requireAfterStart(now);
    }

    void fail(String reason, Instant now) {
        requireStatus(StageAttemptStatus.RUNNING);
        status = StageAttemptStatus.FAILED;
        failureReason = DomainText.require(reason, "attempt failure reason");
        finishedAt = requireAfterStart(now);
    }

    void cancel(Instant now) {
        if (status == StageAttemptStatus.RUNNING
                || status == StageAttemptStatus.WAITING_INPUT
                || status == StageAttemptStatus.WAITING_APPROVAL) {
            status = StageAttemptStatus.CANCELLED;
            finishedAt = requireAfterStart(now);
        }
    }

    private void validateRestoredState() {
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("attempt finished time must not precede start");
        }
        boolean terminal = status == StageAttemptStatus.PASSED
                || status == StageAttemptStatus.FAILED
                || status == StageAttemptStatus.CANCELLED;
        if (terminal != (finishedAt != null)) {
            throw new IllegalArgumentException("attempt terminal status and finished time must agree");
        }
        if (status == StageAttemptStatus.FAILED
                && (failureReason == null || failureReason.trim().isEmpty())) {
            throw new IllegalArgumentException("failed attempt requires failure reason");
        }
    }

    private Instant requireAfterStart(Instant now) {
        Instant value = DomainText.requireTime(now, "attempt transition time");
        if (value.isBefore(startedAt)) {
            throw new IllegalArgumentException("attempt transition time must not move backwards");
        }
        return value;
    }

    private void requireStatus(StageAttemptStatus required) {
        if (status != required) {
            throw new IllegalHarnessTransitionException(
                    "attempt " + number + " cannot transition from " + status);
        }
    }
}
