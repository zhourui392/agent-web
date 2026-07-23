package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

/**
 * RuntimeExecution 写请求的轻量响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessExecutionResult {

    private final String executionId;
    private final String runId;
    private final HarnessStage stage;
    private final String status;
    private final boolean duplicated;
    private final int attemptNumber;

    public HarnessExecutionResult(String executionId, String runId, HarnessStage stage,
                                  String status, boolean duplicated, int attemptNumber) {
        this.executionId = executionId;
        this.runId = runId;
        this.stage = stage;
        this.status = status;
        this.duplicated = duplicated;
        this.attemptNumber = attemptNumber;
    }
}
