package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;

/**
 * 单个受控部署步骤的非敏感执行证据。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentStepResult {

    private final DeploymentStep step;
    private final int exitCode;
    private final String outputSha256;
    private final String outputSummary;
    private final Instant startedAt;
    private final Instant finishedAt;

    public DeploymentStepResult(DeploymentStep step, int exitCode, String outputSha256,
                                String outputSummary, Instant startedAt, Instant finishedAt) {
        if (step == null) {
            throw new IllegalArgumentException("deployment result step is required");
        }
        this.step = step;
        this.exitCode = exitCode;
        this.outputSha256 = DomainText.requireSha256(
                outputSha256, "deployment output sha256");
        this.outputSummary = DomainText.require(
                outputSummary, "deployment output summary", 4000);
        this.startedAt = DomainText.requireTime(startedAt, "deployment step start time");
        this.finishedAt = DomainText.requireTime(finishedAt, "deployment step finish time");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("deployment step finish cannot precede start");
        }
    }

    public boolean passed() {
        return exitCode == 0;
    }
}
