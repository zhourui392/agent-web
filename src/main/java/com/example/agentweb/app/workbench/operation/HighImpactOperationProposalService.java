package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationProposalReceipt;
import com.example.agentweb.domain.workbench.HighImpactOperationProposalRepository;
import com.example.agentweb.domain.workbench.HighImpactOperationRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 高影响操作类型化提案的 Owner、Run Snapshot、策略与持久化编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class HighImpactOperationProposalService {

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchRunSnapshotRepository runSnapshotRepository;
    private final HighImpactOperationRepository operationRepository;
    private final HighImpactOperationProposalRepository proposalRepository;
    private final HighImpactOperationIdGenerator idGenerator;
    private final HighImpactOperationPolicy policy;
    private final Clock clock;
    private final WorkbenchTelemetry telemetry;

    public HighImpactOperationProposalService(
            WorkbenchRepository workbenchRepository,
            WorkbenchRunSnapshotRepository runSnapshotRepository,
            HighImpactOperationRepository operationRepository,
            HighImpactOperationProposalRepository proposalRepository,
            HighImpactOperationIdGenerator idGenerator,
            HighImpactOperationPolicy policy, Clock clock,
            WorkbenchTelemetry telemetry) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.runSnapshotRepository = Objects.requireNonNull(
                runSnapshotRepository, "runSnapshotRepository");
        this.operationRepository = Objects.requireNonNull(
                operationRepository, "operationRepository");
        this.proposalRepository = Objects.requireNonNull(
                proposalRepository, "proposalRepository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Transactional
    public HighImpactOperationProjection propose(
            OwnerReference actor, WorkbenchId workbenchId,
            ProposeHighImpactOperationCommand command) {
        Objects.requireNonNull(command, "command");
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        workbench.requireOperableBy(actor);
        Optional<HighImpactOperationProposalReceipt> existing =
                proposalRepository.find(
                        actor, workbenchId, command.getIdempotencyKey());
        if (existing.isPresent()) {
            String operationId = existing.get().requireReplay(
                    actor, workbenchId, command.getIdempotencyKey(),
                    command.getRequestHash());
            return HighImpactOperationProjection.from(
                    operationRepository.findById(operationId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "operation proposal receipt points to a missing operation")));
        }
        WorkbenchRunSnapshot sourceRun = runSnapshotRepository
                .findByRunId(command.getSourceRunId())
                .orElseThrow(HighImpactOperationProposalService::sourceRunNotFound);
        Instant now = clock.instant();
        HighImpactOperation operation = policy.propose(
                workbench, idGenerator.nextId(), sourceRun, command.getPhase(),
                command.getTarget(), command.getSafeSummary(), actor, now);
        HighImpactOperationProposalReceipt receipt =
                HighImpactOperationProposalReceipt.record(
                        actor, workbenchId, command.getIdempotencyKey(),
                        command.getRequestHash(), operation.getOperationId(), now);
        operationRepository.add(operation);
        proposalRepository.add(receipt);
        telemetry.operation(operation.getType(), operation.getStatus().name());
        return HighImpactOperationProjection.from(operation);
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(HighImpactOperationProposalService::workbenchNotFound);
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

    private static OperationApplicationException workbenchNotFound() {
        return new OperationApplicationException(
                OperationApplicationErrorCode.WORKBENCH_NOT_FOUND,
                "workbench was not found");
    }

    private static OperationApplicationException sourceRunNotFound() {
        return new OperationApplicationException(
                OperationApplicationErrorCode.SOURCE_RUN_NOT_FOUND,
                "source workbench run was not found");
    }
}
