package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.WorkbenchId;

import java.util.List;

/**
 * 高影响操作 CQRS 读侧；只返回公开投影，不返回聚合或 ORM 类型。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HighImpactOperationQueryService {

    List<HighImpactOperationProjection> findByWorkbenchId(
            WorkbenchId workbenchId);
}
