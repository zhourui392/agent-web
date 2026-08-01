package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationDecision;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 高影响操作的 Owner 查询和显式决策编排；MVP 默认不启动 Executor。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class HighImpactOperationOwnerService {

    private final WorkbenchRepository workbenchRepository;
    private final HighImpactOperationRepository operationRepository;
    private final HighImpactOperationQueryService queryService;
    private final HighImpactOperationPolicy policy;
    private final Clock clock;
    private final WorkbenchTelemetry telemetry;

    public HighImpactOperationOwnerService(
            WorkbenchRepository workbenchRepository,
            HighImpactOperationRepository operationRepository,
            HighImpactOperationQueryService queryService,
            HighImpactOperationPolicy policy, Clock clock,
            WorkbenchTelemetry telemetry) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.operationRepository = Objects.requireNonNull(
                operationRepository, "operationRepository");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public HighImpactOperationProjection find(
            OwnerReference actor, WorkbenchId workbenchId,
            String operationId) {
        requireOwnedWorkbench(actor, workbenchId);
        return HighImpactOperationProjection.from(
                requireOperation(workbenchId, operationId));
    }

    public List<HighImpactOperationProjection> list(
            OwnerReference actor, WorkbenchId workbenchId) {
        requireOwnedWorkbench(actor, workbenchId);
        return queryService.findByWorkbenchId(workbenchId);
    }

    @Transactional
    public HighImpactOperationProjection decide(
            OwnerReference actor, WorkbenchId workbenchId,
            String operationId, long expectedVersion,
            HighImpactOperationDecision decision, String reason) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        workbench.requireOperableBy(actor);
        HighImpactOperation operation = requireOperation(
                workbenchId, operationId);
        try {
            operation.requireExpectedVersion(expectedVersion);
            policy.decide(
                    workbench, operation, actor, decision, reason,
                    clock.instant());
            operationRepository.update(operation);
            telemetry.operation(
                    operation.getType(), operation.getStatus().name());
            return HighImpactOperationProjection.from(operation);
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() != WorkbenchErrorCode.VERSION_CONFLICT) {
                throw failure;
            }
            telemetry.writeConflict();
            HighImpactOperationProjection current = operationRepository
                    .findById(operationId)
                    .map(HighImpactOperationProjection::from)
                    .orElse(null);
            throw new OperationApplicationException(
                    OperationApplicationErrorCode.VERSION_CONFLICT,
                    "workbench high-impact operation version conflict", current);
        }
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(HighImpactOperationOwnerService::workbenchNotFound);
        try {
            workbench.requireOwnedBy(actor);
            return workbench;
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw workbenchNotFound();
            }
            throw failure;
        }
    }

    private HighImpactOperation requireOperation(
            WorkbenchId workbenchId, String operationId) {
        HighImpactOperation operation = operationRepository.findById(operationId)
                .orElseThrow(HighImpactOperationOwnerService::operationNotFound);
        try {
            operation.requireWorkbench(workbenchId);
            return operation;
        } catch (IllegalArgumentException failure) {
            throw operationNotFound();
        }
    }

    private static OperationApplicationException workbenchNotFound() {
        return new OperationApplicationException(
                OperationApplicationErrorCode.WORKBENCH_NOT_FOUND,
                "workbench was not found");
    }

    private static OperationApplicationException operationNotFound() {
        return new OperationApplicationException(
                OperationApplicationErrorCode.OPERATION_NOT_FOUND,
                "high-impact operation was not found");
    }
}
