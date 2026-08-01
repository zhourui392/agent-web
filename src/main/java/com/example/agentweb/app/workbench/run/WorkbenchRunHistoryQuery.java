package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.WorkbenchId;

import java.util.Optional;

/**
 * Workbench Run 列表与详情的 CQRS 读端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchRunHistoryQuery {

    WorkbenchRunListPage list(
            WorkbenchId workbenchId, String repositoryScopeHash,
            WorkbenchRunListRequest request);

    Optional<WorkbenchRunDetailView> findDetail(
            WorkbenchId workbenchId, String repositoryScopeHash,
            String runId);
}
