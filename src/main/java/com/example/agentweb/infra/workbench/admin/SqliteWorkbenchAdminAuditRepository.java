package com.example.agentweb.infra.workbench.admin;

import com.example.agentweb.domain.workbench.WorkbenchAdminAuditEntry;
import com.example.agentweb.domain.workbench.WorkbenchAdminAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;

/**
 * Workbench Admin 运维动作的 SQLite 追加式审计实现。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteWorkbenchAdminAuditRepository
        implements WorkbenchAdminAuditRepository {

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchAdminAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void add(WorkbenchAdminAuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        jdbc.update(
                "INSERT INTO workbench_admin_audit "
                        + "(workbench_id, run_id, action, outcome, "
                        + "administrator_id, administrator_name, occurred_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                entry.getWorkbenchId().getValue(), entry.getRunId(),
                entry.getAction().name(), entry.getOutcome(),
                entry.getAdministrator().getActorId(),
                entry.getAdministrator().getActorName(),
                entry.getOccurredAt().toEpochMilli());
    }
}
