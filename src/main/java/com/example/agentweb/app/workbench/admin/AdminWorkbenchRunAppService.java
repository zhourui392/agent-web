package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.app.chatrun.ChatRunRecoveryService;
import com.example.agentweb.app.workbench.run.WorkbenchRunCancellationCoordinator;
import com.example.agentweb.app.workbench.run.WorkbenchRunCancellationResult;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryDecision;
import com.example.agentweb.domain.workbench.WorkbenchAdminAction;
import com.example.agentweb.domain.workbench.WorkbenchAdminAuditEntry;
import com.example.agentweb.domain.workbench.WorkbenchAdminAuditRepository;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Admin Workbench Run 停止与显式单 Run 对账编排。
 *
 * <p>本服务不接收 OwnerReference，也不提供提交 Run、Handoff、Override、Review Confirmation 或操作决策能力。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class AdminWorkbenchRunAppService {

    private final AdminWorkbenchRunAccessResolver accessResolver;
    private final WorkbenchRunCancellationCoordinator cancellationCoordinator;
    private final ChatRunRecoveryService recoveryService;
    private final WorkbenchAdminAuditRepository auditRepository;
    private final Clock clock;

    public AdminWorkbenchRunAppService(
            AdminWorkbenchRunAccessResolver accessResolver,
            WorkbenchRunCancellationCoordinator cancellationCoordinator,
            ChatRunRecoveryService recoveryService,
            WorkbenchAdminAuditRepository auditRepository,
            Clock clock) {
        this.accessResolver = Objects.requireNonNull(
                accessResolver, "accessResolver");
        this.cancellationCoordinator = Objects.requireNonNull(
                cancellationCoordinator, "cancellationCoordinator");
        this.recoveryService = Objects.requireNonNull(
                recoveryService, "recoveryService");
        this.auditRepository = Objects.requireNonNull(
                auditRepository, "auditRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public AdminWorkbenchRunActionResult stop(
            WorkbenchAdministrator administrator,
            WorkbenchId workbenchId, String runId) {
        requireAdministrator(administrator);
        AdminControlledWorkbenchRun controlled =
                accessResolver.requireExact(workbenchId, runId);
        WorkbenchRunCancellationResult cancellation =
                cancellationCoordinator.cancel(
                        controlled.getSnapshot(), controlled.getRun());
        Instant now = clock.instant();
        String outcome = cancellation.getDecision().name();
        auditRepository.add(WorkbenchAdminAuditEntry.record(
                administrator, workbenchId, runId,
                WorkbenchAdminAction.STOP, outcome, now));
        return new AdminWorkbenchRunActionResult(
                workbenchId.getValue(), runId,
                WorkbenchAdminAction.STOP, outcome,
                cancellation.getStopResult().getStatus().name(),
                now.toEpochMilli());
    }

    public AdminWorkbenchRunActionResult reconcile(
            WorkbenchAdministrator administrator,
            WorkbenchId workbenchId, String runId) {
        requireAdministrator(administrator);
        AdminControlledWorkbenchRun controlled =
                accessResolver.requireExact(workbenchId, runId);
        Instant now = clock.instant();
        ChatRunRecoveryDecision decision;
        try {
            decision = recoveryService.reconcileOne(
                    controlled.getRun().getId());
        } catch (RuntimeException failure) {
            auditRepository.add(WorkbenchAdminAuditEntry.record(
                    administrator, workbenchId, runId,
                    WorkbenchAdminAction.RECONCILE, "FAILED", now));
            throw new AdminWorkbenchReconciliationException(failure);
        }
        auditRepository.add(WorkbenchAdminAuditEntry.record(
                administrator, workbenchId, runId,
                WorkbenchAdminAction.RECONCILE,
                decision.name(), now));
        return new AdminWorkbenchRunActionResult(
                workbenchId.getValue(), runId,
                WorkbenchAdminAction.RECONCILE,
                decision.name(), null, now.toEpochMilli());
    }

    private void requireAdministrator(
            WorkbenchAdministrator administrator) {
        if (administrator == null) {
            throw new IllegalArgumentException(
                    "workbench administrator is required");
        }
    }
}
