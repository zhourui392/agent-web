package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

/**
 * 一次 local 部署外部动作聚合根。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentExecution {

    private final String executionId;
    private final String idempotencyKey;
    private final String runId;
    private final int attemptNumber;
    private final String approvedInputBaselineHash;
    private final WorkspaceBaseline workspaceBaseline;
    private final DeploymentTemplateReference template;
    private final Instant preparedAt;
    private DeploymentExecutionStatus status;
    private String failureReason;
    private Instant startedAt;
    private Instant finishedAt;

    private DeploymentExecution(String executionId, String idempotencyKey, String runId,
                                int attemptNumber, String approvedInputBaselineHash,
                                WorkspaceBaseline workspaceBaseline,
                                DeploymentTemplateReference template,
                                DeploymentExecutionStatus status, String failureReason,
                                Instant preparedAt, Instant startedAt, Instant finishedAt) {
        this.executionId = DomainText.require(executionId, "deployment execution id", 128);
        this.idempotencyKey = DomainText.require(idempotencyKey,
                "deployment execution idempotency key", 128);
        this.runId = DomainText.require(runId, "deployment execution run id", 128);
        if (attemptNumber < 1 || workspaceBaseline == null || template == null || status == null) {
            throw new IllegalArgumentException("deployment execution identity is incomplete");
        }
        this.attemptNumber = attemptNumber;
        this.approvedInputBaselineHash = DomainText.requireSha256(
                approvedInputBaselineHash, "deployment approved input baseline hash");
        this.workspaceBaseline = workspaceBaseline;
        this.template = template;
        this.status = status;
        this.failureReason = failureReason;
        this.preparedAt = DomainText.requireTime(preparedAt, "deployment prepared time");
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        validateRestoredState();
    }

    public static DeploymentExecution prepare(String executionId, String idempotencyKey,
                                              DeploymentPermit permit,
                                              DeploymentTemplateReference template,
                                              Instant preparedAt) {
        if (permit == null) {
            throw new IllegalArgumentException("deployment permit must not be null");
        }
        return new DeploymentExecution(executionId, idempotencyKey, permit.getRunId(),
                permit.getAttemptNumber(), permit.getApprovedInputBaselineHash(),
                permit.getWorkspaceBaseline(), template, DeploymentExecutionStatus.PREPARED,
                null, preparedAt, null, null);
    }

    public static DeploymentExecution restore(String executionId, String idempotencyKey,
                                              String runId, int attemptNumber,
                                              String approvedInputBaselineHash,
                                              WorkspaceBaseline workspaceBaseline,
                                              DeploymentTemplateReference template,
                                              DeploymentExecutionStatus status,
                                              String failureReason, Instant preparedAt,
                                              Instant startedAt, Instant finishedAt) {
        return new DeploymentExecution(executionId, idempotencyKey, runId, attemptNumber,
                approvedInputBaselineHash, workspaceBaseline, template, status, failureReason,
                preparedAt, startedAt, finishedAt);
    }

    public boolean begin(WorkspaceBaseline currentBaseline, Instant now) {
        if (status != DeploymentExecutionStatus.PREPARED) {
            return false;
        }
        Instant transitionTime = transitionTime(now);
        if (!workspaceBaseline.sameWorkspaceState(currentBaseline)) {
            status = DeploymentExecutionStatus.FAILED;
            failureReason = "deployment workspace baseline changed";
            finishedAt = transitionTime;
            return false;
        }
        status = DeploymentExecutionStatus.RUNNING;
        startedAt = transitionTime;
        return true;
    }

    public void succeed(Instant now) {
        requireRunning();
        status = DeploymentExecutionStatus.SUCCEEDED;
        finishedAt = transitionTime(now);
    }

    public void fail(String reason, Instant now) {
        requireRunning();
        failureReason = DomainText.require(reason, "deployment failure reason", 1000);
        status = DeploymentExecutionStatus.FAILED;
        finishedAt = transitionTime(now);
    }

    public void complete(DeploymentOutcome outcome, Instant now) {
        if (outcome == null) {
            throw new IllegalArgumentException("deployment outcome is required");
        }
        if (outcome.isSuccessful()) {
            succeed(now);
        } else {
            fail(outcome.getFailureReason(), now);
        }
    }

    public boolean requireReconciliation(String reason, Instant now) {
        if (status == DeploymentExecutionStatus.RECONCILIATION_REQUIRED) {
            return false;
        }
        if (status != DeploymentExecutionStatus.PREPARED
                && status != DeploymentExecutionStatus.RUNNING) {
            return false;
        }
        failureReason = DomainText.require(reason, "deployment reconciliation reason", 1000);
        status = DeploymentExecutionStatus.RECONCILIATION_REQUIRED;
        transitionTime(now);
        return true;
    }

    public void reconcileAsFailed(String reason, Instant now) {
        if (status != DeploymentExecutionStatus.RECONCILIATION_REQUIRED) {
            throw new IllegalHarnessTransitionException(
                    "deployment execution does not require reconciliation");
        }
        failureReason = DomainText.require(reason, "deployment reconciliation result", 1000);
        status = DeploymentExecutionStatus.FAILED;
        finishedAt = transitionTime(now);
    }

    public void requireSameRequest(String requestedRunId, String requestedInputBaselineHash,
                                   DeploymentTemplateReference requestedTemplate) {
        if (!runId.equals(DomainText.require(requestedRunId, "deployment requested run id", 128))
                || !approvedInputBaselineHash.equals(DomainText.requireSha256(
                        requestedInputBaselineHash, "deployment requested input baseline hash"))
                || !template.sameIdentity(requestedTemplate)) {
            throw new DeploymentExecutionIdempotencyConflictException();
        }
    }

    public boolean requiresFailureProjection() {
        return status == DeploymentExecutionStatus.FAILED;
    }

    /**
     * 暴露部署终态的不可变投影，避免 HarnessRun 读取另一个聚合根。
     *
     * @return 非终态为空，终态为不可变 Outcome
     */
    public Optional<DeploymentExecutionOutcome> outcome() {
        if (!status.isTerminal()) {
            return Optional.empty();
        }
        return Optional.of(new DeploymentExecutionOutcome(
                executionId, runId, status, failureReason));
    }

    public void requireRun(String requestedRunId) {
        if (!runId.equals(DomainText.require(
                requestedRunId, "deployment requested run id", 128))) {
            throw new DeploymentExecutionIdempotencyConflictException();
        }
    }

    private void requireRunning() {
        if (status != DeploymentExecutionStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "deployment execution is not running: " + status);
        }
    }

    private Instant transitionTime(Instant now) {
        Instant value = DomainText.requireTime(now, "deployment transition time");
        if (value.isBefore(preparedAt)) {
            throw new IllegalArgumentException("deployment transition cannot precede prepare");
        }
        return value;
    }

    private void validateRestoredState() {
        if (status.isTerminal() != (finishedAt != null)) {
            throw new IllegalArgumentException(
                    "deployment terminal status and finish time must agree");
        }
        if ((status == DeploymentExecutionStatus.RUNNING
                || status == DeploymentExecutionStatus.SUCCEEDED) && startedAt == null) {
            throw new IllegalArgumentException("started deployment requires start time");
        }
    }
}
