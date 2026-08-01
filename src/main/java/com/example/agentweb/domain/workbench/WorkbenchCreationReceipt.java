package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import lombok.Getter;

import java.time.Instant;

/**
 * 将一次规范化创建请求绑定到唯一 Workbench 的幂等收据。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchCreationReceipt {

    private final OwnerReference owner;
    private final String idempotencyKey;
    private final String requestHash;
    private final WorkbenchId workbenchId;
    private final Instant createdAt;

    private WorkbenchCreationReceipt(
            OwnerReference owner, String idempotencyKey, String requestHash,
            WorkbenchId workbenchId, Instant createdAt) {
        if (owner == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "creation receipt owner and workbench id are required");
        }
        this.owner = owner;
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "workbench creation idempotency key", 128);
        this.requestHash = DomainText.requireSha256(
                requestHash, "workbench creation request hash");
        this.workbenchId = workbenchId;
        this.createdAt = DomainText.requireTime(
                createdAt, "workbench creation receipt time");
    }

    public static WorkbenchCreationReceipt record(
            OwnerReference owner, String idempotencyKey, String requestHash,
            WorkbenchId workbenchId, Instant createdAt) {
        return new WorkbenchCreationReceipt(
                owner, idempotencyKey, requestHash, workbenchId, createdAt);
    }

    public static WorkbenchCreationReceipt restore(
            OwnerReference owner, String idempotencyKey, String requestHash,
            WorkbenchId workbenchId, Instant createdAt) {
        return new WorkbenchCreationReceipt(
                owner, idempotencyKey, requestHash, workbenchId, createdAt);
    }

    public WorkbenchId requireReplay(
            OwnerReference actor, String candidateIdempotencyKey,
            String candidateRequestHash) {
        if (!owner.sameIdentityAs(actor)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OWNER_REQUIRED,
                    "only the creation owner can replay the workbench request");
        }
        String key = DomainText.require(
                candidateIdempotencyKey,
                "workbench creation idempotency key", 128);
        String hash = DomainText.requireSha256(
                candidateRequestHash, "workbench creation request hash");
        if (!idempotencyKey.equals(key) || !requestHash.equals(hash)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "workbench creation idempotency key belongs to another request");
        }
        return workbenchId;
    }

    public void requirePreparedFacts(
            Workbench workbench, WorkspaceSnapshot snapshot) {
        if (workbench == null || snapshot == null
                || !workbenchId.equals(workbench.getId())
                || !owner.equals(workbench.getOwner())
                || !createdAt.equals(workbench.getCreatedAt())
                || !snapshot.reference().equals(
                workbench.getCreationSnapshotReference())) {
            throw new IllegalArgumentException(
                    "creation receipt, workbench and snapshot facts must match");
        }
    }
}
