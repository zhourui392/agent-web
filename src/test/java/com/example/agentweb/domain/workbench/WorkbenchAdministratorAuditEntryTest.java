package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Admin Workbench 运维动作的真实 actor 与审计事实领域测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchAdministratorAuditEntryTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T20:00:00Z");

    @Test
    void shouldRecordAdministratorInsteadOfWorkbenchOwnerIdentity() {
        WorkbenchAdministrator administrator =
                administrator("admin-7", "On Call Admin");

        WorkbenchAdminAuditEntry entry = WorkbenchAdminAuditEntry.record(
                administrator, WorkbenchId.of("workbench-1"), "run-1",
                WorkbenchAdminAction.STOP, "REQUESTED", NOW);

        assertEquals("admin-7", entry.getAdministrator().getActorId());
        assertEquals("On Call Admin", entry.getAdministrator().getActorName());
        assertEquals(WorkbenchAdminAction.STOP, entry.getAction());
        assertEquals("REQUESTED", entry.getOutcome());
        assertEquals(NOW, entry.getOccurredAt());
    }

    @Test
    void shouldRejectMissingOrUnsafeAuditFacts() {
        WorkbenchAdministrator administrator =
                administrator("admin-7", "On Call Admin");

        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchAdministrator.fromAuthenticated(
                        new LoginUser("user-1", "Normal User", null,
                                UserRole.USER)));
        assertThrows(IllegalArgumentException.class,
                () -> administrator(" ", "Admin"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchAdminAuditEntry.record(
                        administrator, WorkbenchId.of("workbench-1"),
                        "run-1", WorkbenchAdminAction.RECONCILE,
                        "/workspace/secret.env", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchAdminAuditEntry.record(
                        administrator, WorkbenchId.of("workbench-1"),
                        "run-1", WorkbenchAdminAction.RECONCILE,
                        "RETAIN_ACTIVE", null));
    }

    private WorkbenchAdministrator administrator(
            String actorId, String actorName) {
        return WorkbenchAdministrator.fromAuthenticated(
                new LoginUser(actorId, actorName, null, UserRole.ADMIN));
    }
}
