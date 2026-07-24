package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 当前 DEPLOYMENT Attempt 的只读执行准备状态。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class DeploymentReadiness {

    private final String runId;
    private final String environment;
    private final int attemptNumber;
    private final String inputBaselineHash;
    private final boolean approved;

    public DeploymentReadiness(String runId, String environment, int attemptNumber,
                               String inputBaselineHash, boolean approved) {
        this.runId = runId;
        this.environment = environment;
        this.attemptNumber = attemptNumber;
        this.inputBaselineHash = inputBaselineHash;
        this.approved = approved;
    }
}
