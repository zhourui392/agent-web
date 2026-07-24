package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * DeploymentExecution 终态向 HarnessRun 投影的不可变领域结果。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class DeploymentExecutionOutcome {

    private final String executionId;
    private final String runId;
    private final DeploymentExecutionStatus status;
    private final String failureReason;

    DeploymentExecutionOutcome(String executionId, String runId,
                               DeploymentExecutionStatus status, String failureReason) {
        this.executionId = DomainText.require(executionId,
                "deployment outcome execution id", 128);
        this.runId = DomainText.require(runId, "deployment outcome run id", 128);
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("deployment execution outcome must be terminal");
        }
        this.status = status;
        this.failureReason = failureReason;
    }

    public boolean isSuccessful() {
        return status == DeploymentExecutionStatus.SUCCEEDED;
    }
}
