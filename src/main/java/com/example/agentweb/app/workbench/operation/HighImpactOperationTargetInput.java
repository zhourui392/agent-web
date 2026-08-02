package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.HighImpactOperationTarget;

/**
 * Interface DTO 到领域 Target 的 Application 边界输入。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HighImpactOperationTargetInput {

    HighImpactOperationTarget toDomainTarget();
}
