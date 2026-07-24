package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * HarnessRun 在独立 local Approval 后签发的部署执行许可。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentPermit {

    private final String runId;
    private final int attemptNumber;
    private final String approvedInputBaselineHash;
    private final WorkspaceBaseline workspaceBaseline;

    public DeploymentPermit(String runId, int attemptNumber,
                            String approvedInputBaselineHash,
                            WorkspaceBaseline workspaceBaseline) {
        this.runId = DomainText.require(runId, "deployment permit run id", 128);
        if (attemptNumber < 1 || workspaceBaseline == null) {
            throw new IllegalArgumentException("deployment permit attempt and workspace are required");
        }
        this.attemptNumber = attemptNumber;
        this.approvedInputBaselineHash = DomainText.requireSha256(
                approvedInputBaselineHash, "deployment permit input baseline hash");
        this.workspaceBaseline = workspaceBaseline;
    }
}
