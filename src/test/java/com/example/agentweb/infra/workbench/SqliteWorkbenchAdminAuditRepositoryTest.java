package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.domain.workbench.WorkbenchAdminAction;
import com.example.agentweb.domain.workbench.WorkbenchAdminAuditEntry;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import com.example.agentweb.infra.workbench.admin.SqliteWorkbenchAdminAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Workbench Admin 追加式审计的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchAdminAuditRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private Workbench workbench;
    private SqliteWorkbenchAdminAuditRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("admin-audit.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "admin-audit-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "admin-audit-workbench");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        repository = new SqliteWorkbenchAdminAuditRepository(jdbc);
    }

    @Test
    void shouldAppendRealAdministratorActionsWithoutOverwritingHistory() {
        WorkbenchAdministrator administrator =
                WorkbenchAdministrator.fromAuthenticated(
                        new LoginUser("admin-7", "On Call Admin", null,
                                UserRole.ADMIN));
        repository.add(WorkbenchAdminAuditEntry.record(
                administrator, workbench.getId(), "run-1",
                WorkbenchAdminAction.STOP, "REQUESTED",
                WorkbenchPersistenceFixtures.NOW));
        repository.add(WorkbenchAdminAuditEntry.record(
                administrator, workbench.getId(), "run-1",
                WorkbenchAdminAction.RECONCILE, "INTERRUPT",
                WorkbenchPersistenceFixtures.NOW.plusSeconds(1)));

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_admin_audit "
                        + "WHERE workbench_id=? AND run_id=?",
                Integer.class, workbench.getId().getValue(), "run-1"));
        assertEquals("admin-7", jdbc.queryForObject(
                "SELECT administrator_id FROM workbench_admin_audit "
                        + "WHERE action='STOP'", String.class));
        assertEquals("INTERRUPT", jdbc.queryForObject(
                "SELECT outcome FROM workbench_admin_audit "
                        + "WHERE action='RECONCILE'", String.class));
    }
}
