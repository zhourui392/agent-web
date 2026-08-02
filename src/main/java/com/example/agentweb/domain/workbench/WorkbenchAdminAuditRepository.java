package com.example.agentweb.domain.workbench;

/**
 * Workbench 管理员追加式审计写端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchAdminAuditRepository {

    void add(WorkbenchAdminAuditEntry entry);
}
