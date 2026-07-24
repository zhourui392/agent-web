package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;

/**
 * 绑定 Artifact 基线 Hash 的人工审批实体。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class Approval {

    private final String approvalId;
    private final HarnessStage stage;
    private final int attempt;
    private final String approvalType;
    private final ApprovalDecision decision;
    private final String artifactBaselineHash;
    private final String decidedBy;
    private final String reason;
    private final Instant decidedAt;
    private boolean valid;
    private Instant invalidatedAt;

    public Approval(String approvalId, HarnessStage stage, int attempt, String approvalType,
                    ApprovalDecision decision, String artifactBaselineHash, String decidedBy,
                    String reason, Instant decidedAt, boolean valid, Instant invalidatedAt) {
        this.approvalId = DomainText.require(approvalId, "approval id", 128);
        if (stage == null) {
            throw new IllegalArgumentException("approval stage must not be null");
        }
        this.stage = stage;
        if (attempt < 1) {
            throw new IllegalArgumentException("approval attempt must be positive");
        }
        this.attempt = attempt;
        this.approvalType = DomainText.require(approvalType, "approval type");
        if (decision == null) {
            throw new IllegalArgumentException("approval decision must not be null");
        }
        this.decision = decision;
        this.artifactBaselineHash = DomainText.requireSha256(
                artifactBaselineHash, "approval artifact baseline hash");
        this.decidedBy = DomainText.require(decidedBy, "approval decision maker", 128);
        this.reason = DomainText.require(reason, "approval reason");
        this.decidedAt = DomainText.requireTime(decidedAt, "approval decided time");
        this.valid = valid;
        this.invalidatedAt = invalidatedAt;
        if (valid && invalidatedAt != null || !valid && invalidatedAt == null) {
            throw new IllegalArgumentException("approval validity and invalidated time must agree");
        }
    }

    public static Approval decide(String approvalId, StageExecution stage,
                                  ApprovalDecision decision, String artifactBaselineHash,
                                  String decidedBy, String reason, Instant decidedAt) {
        return new Approval(approvalId, stage.getStage(), stage.currentAttempt().getNumber(),
                stage.getContract().getApprovalType(), decision, artifactBaselineHash,
                decidedBy, reason, decidedAt, true, null);
    }

    public static Approval approveAction(String approvalId, StageExecution stage,
                                         String approvalType, String artifactBaselineHash,
                                         String decidedBy, String reason, Instant decidedAt) {
        return new Approval(approvalId, stage.getStage(), stage.currentAttempt().getNumber(),
                approvalType, ApprovalDecision.APPROVED, artifactBaselineHash,
                decidedBy, reason, decidedAt, true, null);
    }

    void invalidate(Instant now) {
        if (valid) {
            valid = false;
            invalidatedAt = DomainText.requireTime(now, "approval invalidated time");
        }
    }
}
