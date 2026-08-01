package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Collections;
import java.util.Set;

/**
 * MVP 中固定不可执行的生产写目标；独立类型用于明确拒绝而不是回退为普通 MCP 写。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ProductionWriteTarget implements HighImpactOperationTarget {

    private final HighImpactOperationType type = HighImpactOperationType.PRODUCTION_WRITE;
    private final String environment;
    private final String resourceReference;
    private final String expectedProductionStateHash;
    private final String payloadHash;

    private ProductionWriteTarget(String environment, String resourceReference,
                                  String expectedProductionStateHash) {
        this.environment = WorkbenchText.requireUntrustedText(
                environment, "production environment", 128);
        if ("local".equalsIgnoreCase(this.environment)) {
            throw new IllegalArgumentException(
                    "production write target must not use the local environment");
        }
        this.resourceReference = WorkbenchText.requireUntrustedText(
                resourceReference, "production resource reference", 1024);
        this.expectedProductionStateHash = DomainText.requireSha256(
                expectedProductionStateHash, "production expected state hash");
        this.payloadHash = HighImpactTargetSupport.payloadHash(
                type.name(), canonical -> appendPayload(canonical));
    }

    public static ProductionWriteTarget describe(
            String environment, String resourceReference,
            String expectedProductionStateHash) {
        return new ProductionWriteTarget(
                environment, resourceReference, expectedProductionStateHash);
    }

    @Override
    public String requestedPayloadHash() {
        return payloadHash;
    }

    @Override
    public String expectedStateBinding() {
        return expectedProductionStateHash;
    }

    @Override
    public Set<String> repositoryKeys() {
        return Collections.emptySet();
    }

    @Override
    public boolean executionPermanentlyUnavailable() {
        return true;
    }

    private void appendPayload(StringBuilder canonical) {
        CanonicalHashing.appendFramed(canonical, "environment", environment);
        CanonicalHashing.appendFramed(canonical, "resourceReference", resourceReference);
        CanonicalHashing.appendFramed(
                canonical, "expectedProductionStateHash", expectedProductionStateHash);
    }
}
