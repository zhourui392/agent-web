package com.example.agentweb.domain.verification;

import lombok.Getter;

import java.time.Instant;

/**
 * 验证轮次记录（M4.5 轮次化 L2 的第一步）：每次验证 run 终结落一行，
 * 供轮次视图与熔断策略消费；requirement_artifact 是它的证据源（schema 注释约定）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public class VerificationRound {

    private final long id;
    private final String requirementId;
    private final int round;
    private final String deployRef;
    private final VerificationOutcome verdict;
    private final int failedCount;
    private final String evidenceRef;
    private final Instant createdAt;

    public VerificationRound(long id, String requirementId, int round, String deployRef,
                             VerificationOutcome verdict, int failedCount, String evidenceRef,
                             Instant createdAt) {
        if (requirementId == null || requirementId.isBlank()) {
            throw new IllegalArgumentException("requirementId required");
        }
        if (round < 1) {
            throw new IllegalArgumentException("round must be >= 1, got " + round);
        }
        if (verdict == null) {
            throw new IllegalArgumentException("verdict required");
        }
        this.id = id;
        this.requirementId = requirementId.trim();
        this.round = round;
        this.deployRef = deployRef;
        this.verdict = verdict;
        this.failedCount = Math.max(failedCount, 0);
        this.evidenceRef = evidenceRef == null ? "" : evidenceRef;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /** 工厂：新轮次（id 由落库分配，0 占位）。 */
    public static VerificationRound record(String requirementId, int round, VerificationOutcome verdict,
                                           int failedCount, String evidenceRef) {
        return new VerificationRound(0, requirementId, round, null, verdict, failedCount,
                evidenceRef, Instant.now());
    }
}
