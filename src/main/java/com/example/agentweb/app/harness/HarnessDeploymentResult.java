package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * 部署执行受理结果。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class HarnessDeploymentResult {

    private final String executionId;
    private final String runId;
    private final String status;
    private final boolean duplicated;

    public HarnessDeploymentResult(String executionId, String runId, String status,
                                   boolean duplicated) {
        this.executionId = executionId;
        this.runId = runId;
        this.status = status;
        this.duplicated = duplicated;
    }
}
