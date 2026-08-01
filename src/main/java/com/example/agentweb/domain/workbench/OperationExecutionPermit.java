package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * Domain 在授权与 Preflight 一致后签发的一次类型化执行许可。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class OperationExecutionPermit {

    private final String permitId;
    private final String operationId;
    private final HighImpactOperationType operationType;
    private final String requestedPayloadHash;
    private final String preflightHash;
    private final Instant issuedAt;

    OperationExecutionPermit(String permitId, String operationId,
                             HighImpactOperationType operationType,
                             String requestedPayloadHash, String preflightHash,
                             Instant issuedAt) {
        this.permitId = DomainText.require(permitId, "operation permit id", 128);
        this.operationId = DomainText.require(operationId, "operation id", 128);
        if (operationType == null) {
            throw new IllegalArgumentException("operation permit type must not be null");
        }
        this.operationType = operationType;
        this.requestedPayloadHash = DomainText.requireSha256(
                requestedPayloadHash, "operation permit payload hash");
        this.preflightHash = DomainText.requireSha256(
                preflightHash, "operation permit preflight hash");
        this.issuedAt = DomainText.requireTime(issuedAt, "operation permit issued at");
    }
}
