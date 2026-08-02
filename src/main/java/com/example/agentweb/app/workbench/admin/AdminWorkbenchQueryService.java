package com.example.agentweb.app.workbench.admin;

import java.util.Optional;

/**
 * 独立于 Owner 身份的 Admin Workbench CQRS 读端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface AdminWorkbenchQueryService {

    AdminWorkbenchListPage list(AdminWorkbenchListRequest request);

    Optional<AdminWorkbenchDetailView> findDetail(String workbenchId);

    AdminWorkbenchRunListPage listRuns(
            String workbenchId, AdminWorkbenchRunListRequest request);

    Optional<AdminWorkbenchRunDetailView> findRunDetail(
            String workbenchId, String runId);
}
