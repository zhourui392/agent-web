package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationStatus;
import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * 高影响操作的 Owner-safe 只读投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HighImpactOperationProjection {

    private final String operationId;
    private final String sourceRunId;
    private final WorkbenchPhase phase;
    private final HighImpactOperationType type;
    private final OperationTargetProjection target;
    private final String requestedPayloadHash;
    private final String safeSummary;
    private final HighImpactOperationStatus status;
    private final long proposedAt;
    private final String decisionReason;
    private final Long decidedAt;
    private final Long authorizationExpiresAt;
    private final String preflightHash;
    private final String executionReference;
    private final String failureCode;
    private final long updatedAt;
    private final long version;
    private final boolean executionAvailable;
    private final OperationExecutionMode executionMode;

    private HighImpactOperationProjection(HighImpactOperation operation) {
        this.operationId = operation.getOperationId();
        this.sourceRunId = operation.getSourceRun().getRunId();
        this.phase = operation.getPhase();
        this.type = operation.getType();
        this.target = OperationTargetProjection.from(operation.getTarget());
        this.requestedPayloadHash = operation.getRequestedPayloadHash();
        this.safeSummary = operation.getSafeSummary();
        this.status = operation.getStatus();
        this.proposedAt = operation.getProposedAt().toEpochMilli();
        this.decisionReason = operation.getDecisionReason();
        this.decidedAt = epochMillis(operation.getDecidedAt());
        this.authorizationExpiresAt = epochMillis(
                operation.getAuthorizationExpiresAt());
        this.preflightHash = operation.getPreflightHash();
        this.executionReference = operation.getExecutionReference();
        this.failureCode = operation.getFailureCode();
        this.updatedAt = operation.getUpdatedAt().toEpochMilli();
        this.version = operation.getVersion();
        this.executionAvailable = false;
        this.executionMode = OperationExecutionMode.MANUAL_OR_DEFERRED;
    }

    public static HighImpactOperationProjection from(
            HighImpactOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException(
                    "high-impact operation must not be null");
        }
        return new HighImpactOperationProjection(operation);
    }

    private static Long epochMillis(java.time.Instant value) {
        return value == null ? null : Long.valueOf(value.toEpochMilli());
    }
}
