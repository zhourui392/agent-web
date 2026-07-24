package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

/**
 * 一次受控外部进程执行的聚合根。
 *
 * <p>准备、启动、幂等事件、取消优先、超时、丢失和清理证据均在本聚合内迁移。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimeExecution {

    private final String executionId;
    private final String idempotencyKey;
    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final String snapshotHash;
    private final String promptHash;
    private final AgentRuntime runtime;
    private final Instant preparedAt;
    private RuntimeExecutionStatus status;
    private String runtimeVersion;
    private String runtimeHandle;
    private long lastEventSequence;
    private String terminationReason;
    private Integer exitCode;
    private String evidenceReference;
    private RuntimeCleanupStatus cleanupStatus;
    private Instant startedAt;
    private Instant cancelRequestedAt;
    private Instant finishedAt;

    private RuntimeExecution(String executionId, String idempotencyKey, String runId,
                             HarnessStage stage, int attemptNumber, String snapshotHash,
                             String promptHash, AgentRuntime runtime,
                             RuntimeExecutionStatus status, String runtimeVersion,
                             String runtimeHandle, long lastEventSequence,
                             String terminationReason, Integer exitCode,
                             String evidenceReference, RuntimeCleanupStatus cleanupStatus,
                             Instant preparedAt, Instant startedAt,
                             Instant cancelRequestedAt, Instant finishedAt) {
        this.executionId = DomainText.require(executionId, "runtime execution id", 128);
        this.idempotencyKey = DomainText.require(idempotencyKey,
                "runtime execution idempotency key", 128);
        this.runId = DomainText.require(runId, "runtime execution run id", 128);
        if (stage == null || attemptNumber < 1 || runtime == null || status == null
                || cleanupStatus == null || lastEventSequence < 0L) {
            throw new IllegalArgumentException("runtime execution identity and state are invalid");
        }
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.snapshotHash = DomainText.requireSha256(snapshotHash, "runtime snapshot hash");
        this.promptHash = DomainText.requireSha256(promptHash, "runtime prompt hash");
        this.runtime = runtime;
        this.status = status;
        this.runtimeVersion = runtimeVersion;
        this.runtimeHandle = runtimeHandle;
        this.lastEventSequence = lastEventSequence;
        this.terminationReason = terminationReason;
        this.exitCode = exitCode;
        this.evidenceReference = evidenceReference;
        this.cleanupStatus = cleanupStatus;
        this.preparedAt = DomainText.requireTime(preparedAt, "runtime prepared time");
        this.startedAt = startedAt;
        this.cancelRequestedAt = cancelRequestedAt;
        this.finishedAt = finishedAt;
        validateRestoredState();
    }

    public static RuntimeExecution prepare(String executionId, String idempotencyKey,
                                           ExecutionPermit permit, AgentRuntime runtime,
                                           Instant preparedAt) {
        if (permit == null) {
            throw new IllegalArgumentException("execution permit must not be null");
        }
        return new RuntimeExecution(executionId, idempotencyKey, permit.getRunId(),
                permit.getStage(), permit.getAttemptNumber(), permit.getSnapshotHash(),
                permit.getPromptHash(), runtime, RuntimeExecutionStatus.PREPARED,
                null, null, 0L, null, null, null, RuntimeCleanupStatus.NOT_STARTED,
                preparedAt, null, null, null);
    }

    public static RuntimeExecution restore(String executionId, String idempotencyKey, String runId,
                                           HarnessStage stage, int attemptNumber,
                                           String snapshotHash, String promptHash,
                                           AgentRuntime runtime, RuntimeExecutionStatus status,
                                           String runtimeVersion, String runtimeHandle,
                                           long lastEventSequence, String terminationReason,
                                           Integer exitCode, String evidenceReference,
                                           RuntimeCleanupStatus cleanupStatus, Instant preparedAt,
                                           Instant startedAt, Instant cancelRequestedAt,
                                           Instant finishedAt) {
        return new RuntimeExecution(executionId, idempotencyKey, runId, stage, attemptNumber,
                snapshotHash, promptHash, runtime, status, runtimeVersion, runtimeHandle,
                lastEventSequence, terminationReason, exitCode, evidenceReference,
                cleanupStatus, preparedAt, startedAt, cancelRequestedAt, finishedAt);
    }

    public void markStarting(Instant now) {
        requireStatus(RuntimeExecutionStatus.PREPARED);
        requireTime(now);
        status = RuntimeExecutionStatus.STARTING;
        cleanupStatus = RuntimeCleanupStatus.PENDING;
    }

    public boolean beginLaunch(Instant now) {
        if (status != RuntimeExecutionStatus.PREPARED) {
            return false;
        }
        markStarting(now);
        return true;
    }

    public boolean requestCancellation(String actor, String reason, Instant now) {
        DomainText.require(actor, "runtime cancellation actor", 128);
        String normalizedReason = DomainText.require(reason, "runtime cancellation reason", 1000);
        Instant transitionTime = requireTime(now);
        if (status == RuntimeExecutionStatus.CANCEL_REQUESTED
                || status == RuntimeExecutionStatus.CANCELLED) {
            return false;
        }
        if (status == RuntimeExecutionStatus.PREPARED) {
            status = RuntimeExecutionStatus.CANCELLED;
            terminationReason = normalizedReason;
            cancelRequestedAt = transitionTime;
            finishedAt = transitionTime;
            cleanupStatus = RuntimeCleanupStatus.SUCCEEDED;
            return false;
        }
        if (status != RuntimeExecutionStatus.STARTING && status != RuntimeExecutionStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "runtime execution cannot be cancelled from " + status);
        }
        status = RuntimeExecutionStatus.CANCEL_REQUESTED;
        terminationReason = normalizedReason;
        cancelRequestedAt = transitionTime;
        return true;
    }

    public boolean markLostAfterRestart(String reason, Instant now) {
        if (status == RuntimeExecutionStatus.LOST) {
            return false;
        }
        if (status != RuntimeExecutionStatus.PREPARED
                && status != RuntimeExecutionStatus.STARTING
                && status != RuntimeExecutionStatus.RUNNING
                && status != RuntimeExecutionStatus.CANCEL_REQUESTED) {
            return false;
        }
        status = RuntimeExecutionStatus.LOST;
        terminationReason = DomainText.require(reason, "runtime restart loss reason", 1000);
        cleanupStatus = RuntimeCleanupStatus.FAILED;
        finishedAt = requireTime(now);
        lastEventSequence++;
        return true;
    }

    public boolean apply(RuntimeExecutionSignal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("runtime signal must not be null");
        }
        if (signal.getSequence() <= lastEventSequence) {
            return false;
        }
        requireTime(signal.getOccurredAt());
        switch (signal.getType()) {
            case STARTED:
                applyStarted(signal);
                break;
            case OUTPUT:
                applyOutput(signal);
                break;
            case SUCCEEDED:
                applySucceeded(signal);
                break;
            case FAILED:
                applyFailed(signal, RuntimeExecutionStatus.FAILED);
                break;
            case TIMED_OUT:
                applyFailed(signal, RuntimeExecutionStatus.TIMED_OUT);
                break;
            case CANCELLED:
                applyCancelled(signal);
                break;
            case LOST:
                applyLost(signal);
                break;
            default:
                throw new IllegalHarnessTransitionException(
                        "unsupported runtime signal: " + signal.getType());
        }
        lastEventSequence = signal.getSequence();
        return true;
    }

    /**
     * 在状态迁移前校验成功回调的 Artifact Bundle；取消意图优先，不要求写入成功产物。
     *
     * @param signal Runtime 归一化信号
     * @param bundle Runtime 成功产物
     * @return 可安全进入状态机的信号；合同不满足时转换为显式失败
     */
    public RuntimeExecutionSignal enforceArtifactBundle(RuntimeExecutionSignal signal,
                                                        RuntimeArtifactBundle bundle) {
        if (signal == null) {
            throw new IllegalArgumentException("runtime signal must not be null");
        }
        if (signal.getType() != RuntimeExecutionSignalType.SUCCEEDED
                || status == RuntimeExecutionStatus.CANCEL_REQUESTED) {
            return signal;
        }
        try {
            if (bundle == null) {
                throw new IllegalArgumentException("runtime artifact bundle is missing");
            }
            bundle.requireStage(stage);
            return signal;
        } catch (IllegalArgumentException | IllegalHarnessTransitionException ex) {
            return RuntimeExecutionSignal.failed(signal.getSequence(), signal.getExitCode(),
                    "runtime artifact bundle validation failed", signal.getEvidenceReference(),
                    Boolean.TRUE.equals(signal.getTemporaryConfigCleaned()), signal.getOccurredAt());
        }
    }

    /**
     * 暴露跨聚合投影所需的最小终态事实，不泄漏可变 RuntimeExecution 聚合。
     *
     * @return 非终态为空，终态为不可变 Outcome
     */
    public Optional<RuntimeExecutionOutcome> outcome() {
        if (!status.isTerminal()) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeExecutionOutcome(
                reference(), status, terminationReason));
    }

    public ExecutionReference reference() {
        return new ExecutionReference(executionId, runId, stage, attemptNumber, snapshotHash);
    }

    public void requireSameStartRequest(String requestedRunId, HarnessStage requestedStage) {
        String normalizedRunId = DomainText.require(
                requestedRunId, "requested runtime execution run id", 128);
        if (!runId.equals(normalizedRunId) || stage != requestedStage) {
            throw new RuntimeExecutionIdempotencyConflictException(
                    runId, stage, requestedStage);
        }
    }

    private void applyStarted(RuntimeExecutionSignal signal) {
        requireStatus(RuntimeExecutionStatus.STARTING);
        runtimeVersion = signal.getRuntimeVersion();
        runtimeHandle = signal.getRuntimeHandle();
        startedAt = signal.getOccurredAt();
        status = RuntimeExecutionStatus.RUNNING;
    }

    private void applyOutput(RuntimeExecutionSignal signal) {
        if (status != RuntimeExecutionStatus.RUNNING
                && status != RuntimeExecutionStatus.CANCEL_REQUESTED) {
            throw transition(signal);
        }
        if (signal.getEvidenceReference() != null) {
            evidenceReference = signal.getEvidenceReference();
        }
    }

    private void applySucceeded(RuntimeExecutionSignal signal) {
        if (status == RuntimeExecutionStatus.CANCEL_REQUESTED) {
            finish(signal, RuntimeExecutionStatus.CANCELLED, terminationReason);
            return;
        }
        requireStatus(RuntimeExecutionStatus.RUNNING);
        finish(signal, RuntimeExecutionStatus.SUCCEEDED, signal.getReason());
    }

    private void applyFailed(RuntimeExecutionSignal signal, RuntimeExecutionStatus failedStatus) {
        if (status == RuntimeExecutionStatus.CANCEL_REQUESTED) {
            finish(signal, RuntimeExecutionStatus.CANCELLED, terminationReason);
            return;
        }
        if (status != RuntimeExecutionStatus.STARTING && status != RuntimeExecutionStatus.RUNNING) {
            throw transition(signal);
        }
        finish(signal, failedStatus, signal.getReason());
    }

    private void applyCancelled(RuntimeExecutionSignal signal) {
        if (status != RuntimeExecutionStatus.CANCEL_REQUESTED
                && status != RuntimeExecutionStatus.PREPARED) {
            throw transition(signal);
        }
        finish(signal, RuntimeExecutionStatus.CANCELLED,
                terminationReason == null ? signal.getReason() : terminationReason);
    }

    private void applyLost(RuntimeExecutionSignal signal) {
        if (status != RuntimeExecutionStatus.STARTING && status != RuntimeExecutionStatus.RUNNING) {
            throw transition(signal);
        }
        finish(signal, RuntimeExecutionStatus.LOST, signal.getReason());
    }

    private void finish(RuntimeExecutionSignal signal, RuntimeExecutionStatus finalStatus,
                        String finalReason) {
        status = finalStatus;
        terminationReason = finalReason;
        exitCode = signal.getExitCode();
        if (signal.getEvidenceReference() != null) {
            evidenceReference = signal.getEvidenceReference();
        }
        if (signal.getTemporaryConfigCleaned() != null) {
            cleanupStatus = signal.getTemporaryConfigCleaned().booleanValue()
                    ? RuntimeCleanupStatus.SUCCEEDED : RuntimeCleanupStatus.FAILED;
        }
        finishedAt = signal.getOccurredAt();
    }

    private IllegalHarnessTransitionException transition(RuntimeExecutionSignal signal) {
        return new IllegalHarnessTransitionException(
                "runtime signal " + signal.getType() + " is invalid from " + status);
    }

    private void requireStatus(RuntimeExecutionStatus required) {
        if (status != required) {
            throw new IllegalHarnessTransitionException(
                    "runtime execution cannot transition from " + status + ", required " + required);
        }
    }

    private Instant requireTime(Instant time) {
        Instant value = DomainText.requireTime(time, "runtime transition time");
        if (value.isBefore(preparedAt)) {
            throw new IllegalArgumentException("runtime transition time must not precede prepare");
        }
        if (finishedAt != null && value.isBefore(finishedAt)) {
            throw new IllegalArgumentException("runtime transition time must not move backwards");
        }
        return value;
    }

    private void validateRestoredState() {
        if (status.isTerminal() != (finishedAt != null)) {
            throw new IllegalArgumentException("runtime terminal status and finish time must agree");
        }
        if (requiresStartedTime() && startedAt == null) {
            throw new IllegalArgumentException("active runtime execution requires started time");
        }
        if (cancelRequestedAt != null && cancelRequestedAt.isBefore(preparedAt)) {
            throw new IllegalArgumentException("runtime cancellation cannot precede prepare");
        }
    }

    private boolean requiresStartedTime() {
        return status == RuntimeExecutionStatus.RUNNING
                || status == RuntimeExecutionStatus.CANCEL_REQUESTED;
    }
}
