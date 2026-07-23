package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 确定性 Gate 对某一 Artifact 基线的不可变判定结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class GateResult {

    private final String resultId;
    private final HarnessStage stage;
    private final int attempt;
    private final String rule;
    private final boolean passed;
    private final String artifactBaselineHash;
    private final List<String> evidenceReferences;
    private final String reason;
    private final Instant evaluatedAt;

    public GateResult(String resultId, HarnessStage stage, int attempt, String rule,
                      boolean passed, String artifactBaselineHash,
                      List<String> evidenceReferences, String reason, Instant evaluatedAt) {
        this.resultId = DomainText.require(resultId, "gate result id", 128);
        if (stage == null) {
            throw new IllegalArgumentException("gate stage must not be null");
        }
        this.stage = stage;
        if (attempt < 1) {
            throw new IllegalArgumentException("gate attempt must be positive");
        }
        this.attempt = attempt;
        this.rule = DomainText.require(rule, "gate rule", 128);
        this.passed = passed;
        this.artifactBaselineHash = DomainText.requireSha256(
                artifactBaselineHash, "gate artifact baseline hash");
        this.evidenceReferences = immutableEvidence(evidenceReferences);
        if (!passed && (reason == null || reason.trim().isEmpty())) {
            throw new IllegalArgumentException("failed gate requires a reason");
        }
        this.reason = reason == null ? null : reason.trim();
        this.evaluatedAt = DomainText.requireTime(evaluatedAt, "gate evaluated time");
    }

    private List<String> immutableEvidence(List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("gate evidence must not be null");
        }
        List<String> copy = new ArrayList<String>(values.size());
        for (String value : values) {
            copy.add(DomainText.require(value, "gate evidence reference"));
        }
        return Collections.unmodifiableList(copy);
    }
}
