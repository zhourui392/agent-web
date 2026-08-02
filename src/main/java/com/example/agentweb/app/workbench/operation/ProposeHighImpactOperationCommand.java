package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * 固定 UI/API 来源的规范化高影响操作提案命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ProposeHighImpactOperationCommand {

    private static final String HASH_SCHEMA = "high-impact-operation-proposal@1";

    private final String idempotencyKey;
    private final String sourceRunId;
    private final WorkbenchPhase phase;
    private final HighImpactOperationTarget target;
    private final String safeSummary;
    private final String requestHash;

    public ProposeHighImpactOperationCommand(
            String idempotencyKey, String sourceRunId, WorkbenchPhase phase,
            HighImpactOperationTarget target, String safeSummary) {
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "operation proposal idempotency key", 128);
        this.sourceRunId = DomainText.require(
                sourceRunId, "operation proposal source run id", 128);
        if (phase == null || target == null) {
            throw new IllegalArgumentException(
                    "operation proposal phase and target are required");
        }
        this.phase = phase;
        this.target = target;
        this.safeSummary = DomainText.require(
                safeSummary, "operation proposal safe summary", 2000);
        this.requestHash = computeRequestHash();
    }

    private String computeRequestHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "sourceRunId", sourceRunId);
        CanonicalHashing.appendFramed(canonical, "phase", phase.name());
        CanonicalHashing.appendFramed(
                canonical, "targetHash", target.requestedPayloadHash());
        CanonicalHashing.appendFramed(canonical, "safeSummary", safeSummary);
        return CanonicalHashing.sha256(canonical.toString());
    }
}
