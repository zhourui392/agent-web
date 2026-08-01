package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * 高影响操作写侧 Repository，仅负责聚合生命周期。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HighImpactOperationRepository {

    void add(HighImpactOperation operation);

    Optional<HighImpactOperation> findById(String operationId);

    void update(HighImpactOperation operation);
}
