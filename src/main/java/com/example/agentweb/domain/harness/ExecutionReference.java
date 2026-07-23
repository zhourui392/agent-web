package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * HarnessRun 指向独立 RuntimeExecution 聚合的引用。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class ExecutionReference {

    private final String executionId;
    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final String snapshotHash;

    public ExecutionReference(String executionId, String runId, HarnessStage stage,
                              int attemptNumber, String snapshotHash) {
        this.executionId = DomainText.require(executionId, "execution id", 128);
        this.runId = DomainText.require(runId, "execution run id", 128);
        if (stage == null || attemptNumber < 1) {
            throw new IllegalArgumentException("execution stage and positive attempt are required");
        }
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.snapshotHash = DomainText.requireSha256(snapshotHash, "execution snapshot hash");
    }
}
