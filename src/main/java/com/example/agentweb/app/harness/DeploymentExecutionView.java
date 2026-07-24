package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * 部署执行 CQRS 读模型。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentExecutionView {

    private final String executionId;
    private final String runId;
    private final int attemptNumber;
    private final String status;
    private final String approvedInputBaselineHash;
    private final String templateId;
    private final String templateVersion;
    private final String templateHash;
    private final String failureReason;
    private final long preparedAt;
    private final Long startedAt;
    private final Long finishedAt;

    public DeploymentExecutionView(String executionId, String runId, int attemptNumber,
                                   String status, String approvedInputBaselineHash,
                                   String templateId, String templateVersion, String templateHash,
                                   String failureReason, long preparedAt, Long startedAt,
                                   Long finishedAt) {
        this.executionId = executionId;
        this.runId = runId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.approvedInputBaselineHash = approvedInputBaselineHash;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.templateHash = templateHash;
        this.failureReason = failureReason;
        this.preparedAt = preparedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }
}
