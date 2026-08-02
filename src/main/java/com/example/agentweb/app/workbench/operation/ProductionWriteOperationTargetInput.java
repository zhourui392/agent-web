package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.ProductionWriteTarget;

/**
 * Production Write API 字段到专用领域 Target 的无分支转换。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class ProductionWriteOperationTargetInput
        implements HighImpactOperationTargetInput {

    private final String environment;
    private final String resourceReference;
    private final String expectedProductionStateHash;

    public ProductionWriteOperationTargetInput(
            String environment, String resourceReference,
            String expectedProductionStateHash) {
        this.environment = environment;
        this.resourceReference = resourceReference;
        this.expectedProductionStateHash = expectedProductionStateHash;
    }

    @Override
    public HighImpactOperationTarget toDomainTarget() {
        return ProductionWriteTarget.describe(
                environment, resourceReference, expectedProductionStateHash);
    }
}
