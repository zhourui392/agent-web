package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.app.chatrun.ChatRunRecoveryService;
import com.example.agentweb.app.workbench.run.WorkbenchRunCancellationCoordinator;
import com.example.agentweb.app.workbench.run.WorkbenchRunCancellationResult;
import com.example.agentweb.app.workbench.run.WorkbenchRunStopResult;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunCancellationDecision;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryDecision;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.domain.workbench.WorkbenchAdminAction;
import com.example.agentweb.domain.workbench.WorkbenchAdminAuditEntry;
import com.example.agentweb.domain.workbench.WorkbenchAdminAuditRepository;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin Workbench Run 停止、单 Run 对账与真实 actor 审计编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class AdminWorkbenchRunAppServiceTest {

    private AdminWorkbenchRunAccessResolver accessResolver;
    private WorkbenchRunCancellationCoordinator cancellationCoordinator;
    private ChatRunRecoveryService recoveryService;
    private WorkbenchAdminAuditRepository auditRepository;
    private AdminWorkbenchRunAppService service;

    @BeforeEach
    void setUp() {
        accessResolver = mock(AdminWorkbenchRunAccessResolver.class);
        cancellationCoordinator = mock(
                WorkbenchRunCancellationCoordinator.class);
        recoveryService = mock(ChatRunRecoveryService.class);
        auditRepository = mock(WorkbenchAdminAuditRepository.class);
        service = new AdminWorkbenchRunAppService(
                accessResolver, cancellationCoordinator,
                recoveryService, auditRepository,
                Clock.fixed(AdminWorkbenchRunTestFixtures.NOW,
                        ZoneOffset.UTC));
    }

    @Test
    void stopShouldAuthorizeExactRunAndAuditRealAdministrator() {
        ChatRun run = AdminWorkbenchRunTestFixtures.runningRun();
        AdminControlledWorkbenchRun controlled = controlled(run);
        run.requestCancellation(AdminWorkbenchRunTestFixtures.NOW);
        WorkbenchRunCancellationResult cancellation =
                new WorkbenchRunCancellationResult(
                        ChatRunCancellationDecision.REQUESTED,
                        WorkbenchRunStopResult.from(run));
        when(cancellationCoordinator.cancel(
                controlled.getSnapshot(), run)).thenReturn(cancellation);
        WorkbenchAdministrator administrator =
                administrator("admin-7", "On Call Admin");

        AdminWorkbenchRunActionResult result = service.stop(
                administrator, AdminWorkbenchRunTestFixtures.WORKBENCH_ID,
                "run-1");

        assertEquals("CANCEL_REQUESTED", result.getRunStatus());
        assertEquals("REQUESTED", result.getOutcome());
        InOrder order = inOrder(
                accessResolver, cancellationCoordinator, auditRepository);
        order.verify(accessResolver).requireExact(
                AdminWorkbenchRunTestFixtures.WORKBENCH_ID, "run-1");
        order.verify(cancellationCoordinator).cancel(
                controlled.getSnapshot(), run);
        ArgumentCaptor<WorkbenchAdminAuditEntry> audit =
                ArgumentCaptor.forClass(WorkbenchAdminAuditEntry.class);
        order.verify(auditRepository).add(audit.capture());
        assertEquals("admin-7",
                audit.getValue().getAdministrator().getActorId());
        assertEquals(WorkbenchAdminAction.STOP,
                audit.getValue().getAction());
        assertEquals("REQUESTED", audit.getValue().getOutcome());
    }

    @Test
    void reconcileShouldCallCommonSingleRunRecoveryAndAuditDecision() {
        ChatRun run = AdminWorkbenchRunTestFixtures.runningRun();
        AdminControlledWorkbenchRun controlled = controlled(run);
        when(recoveryService.reconcileOne(run.getId()))
                .thenReturn(ChatRunRecoveryDecision.INTERRUPT);
        WorkbenchAdministrator administrator =
                administrator("admin-8", "Recovery Admin");

        AdminWorkbenchRunActionResult result = service.reconcile(
                administrator, AdminWorkbenchRunTestFixtures.WORKBENCH_ID,
                "run-1");

        assertEquals("INTERRUPT", result.getOutcome());
        verify(recoveryService).reconcileOne(run.getId());
        ArgumentCaptor<WorkbenchAdminAuditEntry> audit =
                ArgumentCaptor.forClass(WorkbenchAdminAuditEntry.class);
        verify(auditRepository).add(audit.capture());
        assertEquals("admin-8",
                audit.getValue().getAdministrator().getActorId());
        assertEquals(WorkbenchAdminAction.RECONCILE,
                audit.getValue().getAction());
    }

    @Test
    void reconcileFailureShouldStillLeaveBoundedAuditWithoutExceptionText() {
        ChatRun run = AdminWorkbenchRunTestFixtures.runningRun();
        controlled(run);
        when(recoveryService.reconcileOne(run.getId()))
                .thenThrow(new IllegalStateException(
                        "/workspace/secret.env provider stderr"));

        AdminWorkbenchReconciliationException failure = assertThrows(
                AdminWorkbenchReconciliationException.class,
                () -> service.reconcile(
                        administrator("admin-9", "Admin"),
                        AdminWorkbenchRunTestFixtures.WORKBENCH_ID,
                        "run-1"));
        assertEquals("workbench run reconciliation failed",
                failure.getMessage());

        ArgumentCaptor<WorkbenchAdminAuditEntry> audit =
                ArgumentCaptor.forClass(WorkbenchAdminAuditEntry.class);
        verify(auditRepository).add(audit.capture());
        assertEquals("FAILED", audit.getValue().getOutcome());
    }

    private AdminControlledWorkbenchRun controlled(ChatRun run) {
        AdminControlledWorkbenchRun controlled =
                AdminControlledWorkbenchRun.verified(
                        AdminWorkbenchRunTestFixtures.workbench(),
                        AdminWorkbenchRunTestFixtures.snapshot(), run);
        when(accessResolver.requireExact(
                AdminWorkbenchRunTestFixtures.WORKBENCH_ID, "run-1"))
                .thenReturn(controlled);
        return controlled;
    }

    private WorkbenchAdministrator administrator(
            String actorId, String actorName) {
        return WorkbenchAdministrator.fromAuthenticated(
                new LoginUser(actorId, actorName, null, UserRole.ADMIN));
    }
}
