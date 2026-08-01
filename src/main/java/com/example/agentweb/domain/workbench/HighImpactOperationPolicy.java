package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;

import java.time.Duration;
import java.time.Instant;

/**
 * 高影响操作的提案、显式 Owner 决策和执行许可策略。
 *
 * <p>Application 只负责加载 Workbench/Run/Preflight 事实；授权与状态绑定在此收敛。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class HighImpactOperationPolicy {

    private final Duration authorizationTtl;

    private HighImpactOperationPolicy(Duration authorizationTtl) {
        if (authorizationTtl == null || authorizationTtl.isZero()
                || authorizationTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "operation authorization TTL must be positive");
        }
        this.authorizationTtl = authorizationTtl;
    }

    public static HighImpactOperationPolicy withAuthorizationTtl(Duration authorizationTtl) {
        return new HighImpactOperationPolicy(authorizationTtl);
    }

    public HighImpactOperation propose(
            Workbench workbench, String operationId, WorkbenchRunReference sourceRun,
            HighImpactOperationTarget target, String safeSummary,
            OwnerReference actor, Instant proposedAt) {
        if (workbench == null || sourceRun == null || target == null) {
            throw new IllegalArgumentException(
                    "operation proposal workbench, source run and target are required");
        }
        workbench.requireOperableBy(actor);
        if (!workbench.getId().equals(sourceRun.getWorkbenchId())) {
            throw new IllegalArgumentException(
                    "operation source run must belong to the workbench");
        }
        for (String repositoryKey : target.repositoryKeys()) {
            if (!workbench.getRepositoryScope().containsRepository(repositoryKey)) {
                throw new IllegalArgumentException(
                        "operation target repository is outside the workbench scope: "
                                + repositoryKey);
            }
        }
        return new HighImpactOperation(
                operationId, workbench.getId(), sourceRun, target,
                safeSummary, actor, proposedAt);
    }

    public void decide(Workbench workbench, HighImpactOperation operation,
                       OwnerReference actor, HighImpactOperationDecision decision,
                       String reason, Instant decidedAt) {
        requireRelatedOwner(workbench, operation, actor);
        if (decision == null) {
            throw new IllegalArgumentException("operation decision must not be null");
        }
        if (decision == HighImpactOperationDecision.APPROVE) {
            operation.authorize(
                    actor, reason, decidedAt,
                    DomainText.requireTime(decidedAt, "operation decided at")
                            .plus(authorizationTtl));
            return;
        }
        operation.reject(actor, reason, decidedAt);
    }

    public OperationExecutionPermit issueExecutionPermit(
            Workbench workbench, HighImpactOperation operation,
            OwnerReference actor, HighImpactPreflightProof preflightProof,
            boolean executorAvailable, String permitId, Instant now) {
        requireRelatedOwner(workbench, operation, actor);
        if (preflightProof == null) {
            throw new IllegalArgumentException("operation preflight proof must not be null");
        }
        Instant permitTime = DomainText.requireTime(now, "operation permit time");
        if (operation.getStatus() != HighImpactOperationStatus.AUTHORIZED) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OPERATION_TRANSITION_INVALID,
                    "only an authorized operation can receive an execution permit");
        }
        if (operation.authorizationExpiredAt(permitTime)) {
            operation.expire(permitTime, "AUTHORIZATION_EXPIRED");
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OPERATION_TRANSITION_INVALID,
                    "operation authorization has expired");
        }
        if (!operation.getRequestedPayloadHash().equals(
                preflightProof.getRequestedPayloadHash())
                || !operation.getTarget().expectedStateBinding().equals(
                preflightProof.getObservedStateBinding())) {
            operation.expire(permitTime, "TARGET_STATE_CHANGED");
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OPERATION_TARGET_CHANGED,
                    "operation target changed after authorization");
        }
        if (!executorAvailable
                || operation.getTarget().executionPermanentlyUnavailable()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OPERATION_EXECUTION_UNAVAILABLE,
                    "operation executor is not enabled for this target type");
        }
        if (preflightProof.getVerifiedAt().isAfter(permitTime)) {
            throw new IllegalArgumentException(
                    "operation preflight cannot be verified in the future");
        }
        return new OperationExecutionPermit(
                permitId, operation.getOperationId(), operation.getType(),
                operation.getRequestedPayloadHash(), preflightProof.getPreflightHash(),
                permitTime);
    }

    private static void requireRelatedOwner(
            Workbench workbench, HighImpactOperation operation, OwnerReference actor) {
        if (workbench == null || operation == null) {
            throw new IllegalArgumentException(
                    "operation and workbench must not be null");
        }
        workbench.requireOperableBy(actor);
        if (!workbench.getId().equals(operation.getWorkbenchId())) {
            throw new IllegalArgumentException(
                    "operation must belong to the workbench");
        }
    }
}
