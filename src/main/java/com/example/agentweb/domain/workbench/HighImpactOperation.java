package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * 一个类型化高影响操作提案、人工决策与执行状态聚合。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HighImpactOperation {

    private final String operationId;
    private final WorkbenchId workbenchId;
    private final WorkbenchRunReference sourceRun;
    private final WorkbenchPhase phase;
    private final HighImpactOperationType type;
    private final HighImpactOperationTarget target;
    private final String requestedPayloadHash;
    private final String safeSummary;
    private HighImpactOperationStatus status;
    private final OwnerReference proposedBy;
    private final Instant proposedAt;
    private OwnerReference decidedBy;
    private String decisionReason;
    private Instant decidedAt;
    private Instant authorizationExpiresAt;
    private String preflightHash;
    private String executionReference;
    private String failureCode;
    private Instant updatedAt;
    private long version;

    HighImpactOperation(String operationId, WorkbenchId workbenchId,
                        WorkbenchRunReference sourceRun,
                        HighImpactOperationTarget target, String safeSummary,
                        OwnerReference proposedBy, Instant proposedAt) {
        this.operationId = DomainText.require(operationId, "operation id", 128);
        if (workbenchId == null || sourceRun == null || target == null
                || proposedBy == null) {
            throw new IllegalArgumentException(
                    "operation workbench, run, target and proposer are required");
        }
        this.workbenchId = workbenchId;
        this.sourceRun = sourceRun;
        this.phase = sourceRun.getPhase();
        this.type = target.getType();
        this.target = target;
        this.requestedPayloadHash = DomainText.requireSha256(
                target.requestedPayloadHash(), "operation requested payload hash");
        this.safeSummary = WorkbenchText.requireUntrustedText(
                safeSummary, "operation safe summary", 2000);
        HighImpactOperationSecretPolicy.requireSafePreview(this.safeSummary);
        this.status = HighImpactOperationStatus.PROPOSED;
        this.proposedBy = proposedBy;
        this.proposedAt = DomainText.requireTime(proposedAt, "operation proposed at");
        this.updatedAt = this.proposedAt;
        this.version = 0L;
    }

    /**
     * 从持久化事实无损恢复聚合，不重放授权或执行副作用。
     *
     * @param operationId operation id
     * @param workbenchId owning workbench
     * @param sourceRun verified source run reference
     * @param target typed target
     * @param requestedPayloadHash persisted payload hash
     * @param safeSummary safe proposal summary
     * @param status persisted lifecycle status
     * @param proposedBy actual proposer
     * @param proposedAt proposal time
     * @param decidedBy actual decision actor, when decided
     * @param decisionReason decision reason, when decided
     * @param decidedAt decision time, when decided
     * @param authorizationExpiresAt authorization expiry, when approved
     * @param preflightHash execution preflight hash, once executing
     * @param executionReference external execution reference, once executing
     * @param failureCode failure/reconciliation/expiry code, when applicable
     * @param updatedAt last persisted transition time
     * @param version optimistic version
     * @return restored aggregate
     */
    public static HighImpactOperation restore(
            String operationId, WorkbenchId workbenchId,
            WorkbenchRunReference sourceRun, HighImpactOperationTarget target,
            String requestedPayloadHash, String safeSummary,
            HighImpactOperationStatus status,
            OwnerReference proposedBy, Instant proposedAt,
            OwnerReference decidedBy, String decisionReason, Instant decidedAt,
            Instant authorizationExpiresAt, String preflightHash,
            String executionReference, String failureCode,
            Instant updatedAt, long version) {
        HighImpactOperation operation = new HighImpactOperation(
                operationId, workbenchId, sourceRun, target,
                safeSummary, proposedBy, proposedAt);
        String persistedPayloadHash = DomainText.requireSha256(
                requestedPayloadHash, "operation requested payload hash");
        if (!operation.requestedPayloadHash.equals(persistedPayloadHash)) {
            throw new IllegalArgumentException(
                    "restored operation payload hash does not match the typed target");
        }
        if (status == null) {
            throw new IllegalArgumentException("operation status must not be null");
        }
        if (version < 0L) {
            throw new IllegalArgumentException("operation version must not be negative");
        }
        operation.status = status;
        operation.decidedBy = decidedBy;
        operation.decisionReason = decisionReason == null ? null
                : WorkbenchText.requireUntrustedText(
                decisionReason, "operation decision reason", 2000);
        operation.decidedAt = decidedAt;
        operation.authorizationExpiresAt = authorizationExpiresAt;
        operation.preflightHash = preflightHash == null ? null
                : DomainText.requireSha256(preflightHash, "operation preflight hash");
        operation.executionReference = executionReference == null ? null
                : DomainText.require(
                executionReference, "operation execution reference", 256);
        operation.failureCode = failureCode == null ? null
                : DomainText.require(failureCode, "operation failure code", 256);
        operation.updatedAt = DomainText.requireTime(updatedAt, "operation updated at");
        operation.version = version;
        operation.validateRestoredState();
        return operation;
    }

    void authorize(OwnerReference actor, String reason, Instant decidedAt,
                   Instant authorizationExpiresAt) {
        requireStatus(HighImpactOperationStatus.PROPOSED);
        Instant decisionTime = requireLifecycleTime(decidedAt, "operation decided at");
        Instant expiry = DomainText.requireTime(
                authorizationExpiresAt, "operation authorization expiry");
        if (!expiry.isAfter(decisionTime)) {
            throw new IllegalArgumentException(
                    "operation authorization expiry must follow the decision");
        }
        OwnerReference decisionActor = requireActor(actor);
        String normalizedReason = WorkbenchText.requireUntrustedText(
                reason, "operation decision reason", 2000);
        this.status = HighImpactOperationStatus.AUTHORIZED;
        this.decidedBy = decisionActor;
        this.decisionReason = normalizedReason;
        this.decidedAt = decisionTime;
        this.authorizationExpiresAt = expiry;
        touch(decisionTime);
    }

    void reject(OwnerReference actor, String reason, Instant decidedAt) {
        requireStatus(HighImpactOperationStatus.PROPOSED);
        Instant decisionTime = requireLifecycleTime(decidedAt, "operation decided at");
        OwnerReference decisionActor = requireActor(actor);
        String normalizedReason = WorkbenchText.requireUntrustedText(
                reason, "operation decision reason", 2000);
        this.status = HighImpactOperationStatus.REJECTED;
        this.decidedBy = decisionActor;
        this.decisionReason = normalizedReason;
        this.decidedAt = decisionTime;
        touch(decisionTime);
    }

    void expire(Instant now, String reason) {
        if (status != HighImpactOperationStatus.AUTHORIZED) {
            throw invalidTransition(HighImpactOperationStatus.EXPIRED);
        }
        Instant expiryTime = requireLifecycleTime(now, "operation expired at");
        String expiryReason = WorkbenchText.requireUntrustedText(
                reason, "operation expiry reason", 256);
        status = HighImpactOperationStatus.EXPIRED;
        failureCode = expiryReason;
        touch(expiryTime);
    }

    public void startExecution(OperationExecutionPermit permit,
                               String executionReference, Instant now) {
        requireStatus(HighImpactOperationStatus.AUTHORIZED);
        if (permit == null || !operationId.equals(permit.getOperationId())
                || type != permit.getOperationType()
                || !requestedPayloadHash.equals(permit.getRequestedPayloadHash())) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OPERATION_TARGET_CHANGED,
                    "execution permit does not match this operation");
        }
        String reference = DomainText.require(
                executionReference, "operation execution reference", 256);
        Instant startedAt = requireLifecycleTime(now, "operation execution started at");
        this.executionReference = reference;
        this.preflightHash = permit.getPreflightHash();
        this.status = HighImpactOperationStatus.EXECUTING;
        touch(startedAt);
    }

    public void succeed(Instant now) {
        requireStatus(HighImpactOperationStatus.EXECUTING);
        Instant succeededAt = requireLifecycleTime(now, "operation succeeded at");
        status = HighImpactOperationStatus.SUCCEEDED;
        touch(succeededAt);
    }

    public void fail(String code, Instant now) {
        requireStatus(HighImpactOperationStatus.EXECUTING);
        String normalizedCode = DomainText.require(code, "operation failure code", 128);
        Instant failedAt = requireLifecycleTime(now, "operation failed at");
        status = HighImpactOperationStatus.FAILED;
        failureCode = normalizedCode;
        touch(failedAt);
    }

    public void requireReconciliation(String code, Instant now) {
        requireStatus(HighImpactOperationStatus.EXECUTING);
        String normalizedCode = DomainText.require(
                code, "operation reconciliation code", 128);
        Instant reconciliationAt = requireLifecycleTime(
                now, "operation reconciliation time");
        status = HighImpactOperationStatus.RECONCILIATION_REQUIRED;
        failureCode = normalizedCode;
        touch(reconciliationAt);
    }

    public boolean authorizationExpiredAt(Instant now) {
        return status == HighImpactOperationStatus.AUTHORIZED
                && authorizationExpiresAt != null
                && !DomainText.requireTime(now, "operation authorization check time")
                .isBefore(authorizationExpiresAt);
    }

    public void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "operation expected version must not be negative");
        }
        if (version != expectedVersion) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "stale high-impact operation version");
        }
    }

    public void requireWorkbench(WorkbenchId expectedWorkbenchId) {
        if (expectedWorkbenchId == null) {
            throw new IllegalArgumentException(
                    "expected operation workbench must not be null");
        }
        if (!workbenchId.equals(expectedWorkbenchId)) {
            throw new IllegalArgumentException(
                    "high-impact operation does not belong to the expected workbench");
        }
    }

    private void requireStatus(HighImpactOperationStatus expected) {
        if (status != expected) {
            throw invalidTransition(expected);
        }
    }

    private WorkbenchDomainException invalidTransition(HighImpactOperationStatus targetStatus) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.OPERATION_TRANSITION_INVALID,
                "operation cannot transition from " + status + " to " + targetStatus);
    }

    private Instant requireLifecycleTime(Instant now, String name) {
        Instant value = DomainText.requireTime(now, name);
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "operation lifecycle time must not move backwards");
        }
        return value;
    }

    private static OwnerReference requireActor(OwnerReference actor) {
        if (actor == null) {
            throw new IllegalArgumentException("operation actor must not be null");
        }
        return actor;
    }

    private void touch(Instant now) {
        updatedAt = now;
        version++;
    }

    private void validateRestoredState() {
        if (updatedAt.isBefore(proposedAt)) {
            throw new IllegalArgumentException(
                    "operation updated time must not precede proposal time");
        }
        boolean decisionComplete = decidedBy != null && decisionReason != null
                && decidedAt != null;
        boolean decisionAbsent = decidedBy == null && decisionReason == null
                && decidedAt == null;
        if (!decisionComplete && !decisionAbsent) {
            throw new IllegalArgumentException(
                    "operation decision fields must be all present or all absent");
        }
        if (decidedAt != null && (decidedAt.isBefore(proposedAt)
                || updatedAt.isBefore(decidedAt))) {
            throw new IllegalArgumentException(
                    "operation decision time is inconsistent with lifecycle times");
        }
        if (authorizationExpiresAt != null && (decidedAt == null
                || !authorizationExpiresAt.isAfter(decidedAt))) {
            throw new IllegalArgumentException(
                    "operation authorization expiry requires an earlier decision");
        }
        switch (status) {
            case PROPOSED:
                requireRestored(decisionAbsent && authorizationExpiresAt == null
                        && preflightHash == null && executionReference == null
                        && failureCode == null,
                        "proposed operation must not contain decision or execution fields");
                break;
            case REJECTED:
                requireRestored(decisionComplete && authorizationExpiresAt == null
                        && preflightHash == null && executionReference == null
                        && failureCode == null,
                        "rejected operation has inconsistent fields");
                break;
            case AUTHORIZED:
                requireRestored(decisionComplete && authorizationExpiresAt != null
                        && preflightHash == null && executionReference == null
                        && failureCode == null,
                        "authorized operation has inconsistent fields");
                break;
            case EXPIRED:
                requireRestored(decisionComplete && authorizationExpiresAt != null
                        && preflightHash == null && executionReference == null
                        && failureCode != null,
                        "expired operation has inconsistent fields");
                break;
            case EXECUTING:
            case SUCCEEDED:
                requireRestored(hasExecutionFields() && failureCode == null,
                        "executing or succeeded operation has inconsistent fields");
                break;
            case FAILED:
            case RECONCILIATION_REQUIRED:
                requireRestored(hasExecutionFields() && failureCode != null,
                        "failed or reconciliation operation has inconsistent fields");
                break;
            default:
                throw new IllegalArgumentException(
                        "unknown restored operation status: " + status);
        }
    }

    private boolean hasExecutionFields() {
        return decidedBy != null && decisionReason != null && decidedAt != null
                && authorizationExpiresAt != null && preflightHash != null
                && executionReference != null;
    }

    private static void requireRestored(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
