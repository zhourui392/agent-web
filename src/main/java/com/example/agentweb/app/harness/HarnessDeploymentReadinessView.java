package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.DeploymentReadiness;
import lombok.Getter;

/**
 * 管理页面使用的 DEPLOYMENT 输入基线和独立 Approval 只读投影。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class HarnessDeploymentReadinessView {

    private final String runId;
    private final String environment;
    private final int attemptNumber;
    private final String inputBaselineHash;
    private final boolean approved;

    public HarnessDeploymentReadinessView(DeploymentReadiness readiness) {
        this.runId = readiness.getRunId();
        this.environment = readiness.getEnvironment();
        this.attemptNumber = readiness.getAttemptNumber();
        this.inputBaselineHash = readiness.getInputBaselineHash();
        this.approved = readiness.isApproved();
    }
}
