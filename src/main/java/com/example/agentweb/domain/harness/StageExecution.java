package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Run 聚合内的阶段实体，保留历史 Attempt 并表达当前有效状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class StageExecution {

    private final StageContract contract;
    private final List<StageAttempt> attempts;
    private StageStatus status;

    private StageExecution(StageContract contract, StageStatus status, List<StageAttempt> attempts) {
        if (contract == null) {
            throw new IllegalArgumentException("stage contract must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("stage status must not be null");
        }
        this.contract = contract;
        this.status = status;
        this.attempts = new ArrayList<StageAttempt>(attempts);
        validateRestoredState();
    }

    public static StageExecution pending(StageContract contract) {
        return new StageExecution(contract, StageStatus.PENDING,
                Collections.<StageAttempt>emptyList());
    }

    public static StageExecution restore(StageContract contract, StageStatus status,
                                         List<StageAttempt> attempts) {
        if (attempts == null) {
            throw new IllegalArgumentException("stage attempts must not be null");
        }
        return new StageExecution(contract, status, attempts);
    }

    public HarnessStage getStage() {
        return contract.getStage();
    }

    public List<StageAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }

    public StageAttempt currentAttempt() {
        if (attempts.isEmpty()) {
            throw new IllegalHarnessTransitionException("stage has no attempt: " + getStage());
        }
        return attempts.get(attempts.size() - 1);
    }

    boolean isCurrentAttempt(String idempotencyKey) {
        return !attempts.isEmpty()
                && currentAttempt().getIdempotencyKey().equals(idempotencyKey)
                && status == StageStatus.RUNNING;
    }

    void startNewAttempt(String idempotencyKey, Instant now) {
        attempts.add(StageAttempt.start(attempts.size() + 1, idempotencyKey, now));
        status = StageStatus.RUNNING;
    }

    void waitForApproval() {
        requireStatus(StageStatus.RUNNING);
        currentAttempt().waitForApproval();
        status = StageStatus.WAITING_APPROVAL;
    }

    void resumeAfterRejection() {
        requireStatus(StageStatus.WAITING_APPROVAL);
        currentAttempt().resumeAfterRejection();
        status = StageStatus.RUNNING;
    }

    void pass(Instant now) {
        requireStatus(StageStatus.WAITING_APPROVAL);
        currentAttempt().pass(now);
        status = StageStatus.PASSED;
    }

    void fail(String reason, Instant now) {
        requireStatus(StageStatus.RUNNING);
        currentAttempt().fail(reason, now);
        status = StageStatus.FAILED;
    }

    void bindSnapshot(String snapshotHash) {
        requireStatus(StageStatus.RUNNING);
        currentAttempt().bindSnapshot(snapshotHash);
    }

    void bindExecution(String executionId) {
        requireStatus(StageStatus.RUNNING);
        currentAttempt().bindExecution(executionId);
    }

    void requestCancellation() {
        if (!status.isWritable()) {
            throw new IllegalHarnessTransitionException(
                    "stage cannot request cancellation from " + status);
        }
        currentAttempt().requestCancellation();
        status = StageStatus.CANCELLING;
    }

    void confirmCancellation(Instant now) {
        requireStatus(StageStatus.CANCELLING);
        currentAttempt().confirmCancellation(now);
        status = StageStatus.CANCELLED;
    }

    void failFromRuntime(String reason, Instant now) {
        if (status != StageStatus.RUNNING && status != StageStatus.WAITING_INPUT) {
            throw new IllegalHarnessTransitionException(
                    "stage cannot fail from runtime while " + status);
        }
        currentAttempt().failFromRuntime(reason, now);
        status = StageStatus.FAILED;
    }

    boolean invalidate() {
        if (status == StageStatus.PENDING || status == StageStatus.INVALIDATED) {
            return false;
        }
        if (status.occupiesActiveAttempt()) {
            throw new IllegalHarnessTransitionException(
                    "cannot invalidate writable stage: " + getStage());
        }
        status = StageStatus.INVALIDATED;
        return true;
    }

    void cancel(Instant now) {
        if (status.isWritable()) {
            currentAttempt().cancel(now);
        }
        if (status != StageStatus.PASSED) {
            status = StageStatus.CANCELLED;
        }
    }

    private void validateRestoredState() {
        if (status == StageStatus.PENDING && !attempts.isEmpty()) {
            throw new IllegalArgumentException("pending stage must not have attempts");
        }
        if (status != StageStatus.PENDING && status != StageStatus.CANCELLED && attempts.isEmpty()) {
            throw new IllegalArgumentException("started stage must have attempts");
        }
    }

    private void requireStatus(StageStatus required) {
        if (status != required) {
            throw new IllegalHarnessTransitionException(
                    "stage " + getStage() + " cannot transition from " + status);
        }
    }
}
