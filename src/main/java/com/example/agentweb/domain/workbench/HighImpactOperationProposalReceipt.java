package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * 将 Owner 范围内的一次规范化高影响操作提案绑定到唯一 Operation。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HighImpactOperationProposalReceipt {

    private final OwnerReference owner;
    private final WorkbenchId workbenchId;
    private final String idempotencyKey;
    private final String requestHash;
    private final String operationId;
    private final Instant createdAt;

    private HighImpactOperationProposalReceipt(
            OwnerReference owner, WorkbenchId workbenchId,
            String idempotencyKey, String requestHash,
            String operationId, Instant createdAt) {
        if (owner == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "operation proposal receipt owner and workbench are required");
        }
        this.owner = owner;
        this.workbenchId = workbenchId;
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "operation proposal idempotency key", 128);
        this.requestHash = DomainText.requireSha256(
                requestHash, "operation proposal request hash");
        this.operationId = DomainText.require(
                operationId, "operation proposal operation id", 128);
        this.createdAt = DomainText.requireTime(
                createdAt, "operation proposal receipt time");
    }

    public static HighImpactOperationProposalReceipt record(
            OwnerReference owner, WorkbenchId workbenchId,
            String idempotencyKey, String requestHash,
            String operationId, Instant createdAt) {
        return new HighImpactOperationProposalReceipt(
                owner, workbenchId, idempotencyKey, requestHash,
                operationId, createdAt);
    }

    public static HighImpactOperationProposalReceipt restore(
            OwnerReference owner, WorkbenchId workbenchId,
            String idempotencyKey, String requestHash,
            String operationId, Instant createdAt) {
        return record(owner, workbenchId, idempotencyKey, requestHash,
                operationId, createdAt);
    }

    public String requireReplay(
            OwnerReference actor, WorkbenchId candidateWorkbenchId,
            String candidateIdempotencyKey, String candidateRequestHash) {
        if (!owner.sameIdentityAs(actor)
                || !workbenchId.equals(candidateWorkbenchId)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OWNER_REQUIRED,
                    "operation proposal replay is outside the owner workbench");
        }
        String key = DomainText.require(
                candidateIdempotencyKey,
                "operation proposal idempotency key", 128);
        String hash = DomainText.requireSha256(
                candidateRequestHash, "operation proposal request hash");
        if (!idempotencyKey.equals(key) || !requestHash.equals(hash)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "operation proposal idempotency key belongs to another request");
        }
        return operationId;
    }
}
