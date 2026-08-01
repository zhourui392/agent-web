package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * 下游阶段明确接收的上游 Handoff 版本；上游更新只产生 stale 语义查询。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffReception {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase targetPhase;
    private final WorkbenchPhase sourcePhase;
    private final long sourceVersion;
    private final String sourceHash;
    private final OwnerReference acceptedBy;
    private final Instant acceptedAt;

    private HandoffReception(WorkbenchId workbenchId, WorkbenchPhase targetPhase,
                             WorkbenchPhase sourcePhase, long sourceVersion,
                             String sourceHash, OwnerReference acceptedBy,
                             Instant acceptedAt) {
        if (workbenchId == null || targetPhase == null || sourcePhase == null
                || acceptedBy == null) {
            throw new IllegalArgumentException(
                    "handoff reception required values must not be null");
        }
        WorkbenchPhase expectedSource = targetPhase.defaultHandoffSource().orElse(null);
        if (expectedSource != sourcePhase) {
            throw new IllegalArgumentException(
                    "handoff source must be the target phase default upstream");
        }
        if (sourceVersion < 0L) {
            throw new IllegalArgumentException(
                    "handoff source version must not be negative");
        }
        this.workbenchId = workbenchId;
        this.targetPhase = targetPhase;
        this.sourcePhase = sourcePhase;
        this.sourceVersion = sourceVersion;
        this.sourceHash = DomainText.requireSha256(sourceHash, "handoff source hash");
        this.acceptedBy = acceptedBy;
        this.acceptedAt = DomainText.requireTime(acceptedAt, "handoff accepted at");
    }

    public static HandoffReception accept(
            WorkbenchId workbenchId, WorkbenchPhase targetPhase,
            WorkbenchPhase sourcePhase, long sourceVersion, String sourceHash,
            OwnerReference acceptedBy, Instant acceptedAt) {
        return new HandoffReception(
                workbenchId, targetPhase, sourcePhase, sourceVersion, sourceHash,
                acceptedBy, acceptedAt);
    }

    public boolean isStale(long latestVersion, String latestHash) {
        String hash = DomainText.requireSha256(latestHash, "latest handoff hash");
        return latestVersion != sourceVersion || !sourceHash.equals(hash);
    }

    /**
     * 要求接收记录仍指向当前上游版本，否则以领域并发冲突拒绝写入。
     *
     * @param latestVersion 当前上游版本
     * @param latestHash 当前上游内容 Hash
     */
    public void requireLatest(long latestVersion, String latestHash) {
        if (isStale(latestVersion, latestHash)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "handoff source version or hash is stale");
        }
    }
}
