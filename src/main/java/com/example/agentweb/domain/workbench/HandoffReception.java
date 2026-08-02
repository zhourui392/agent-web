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

    /**
     * 决定提交事务是否需要首次插入当前 Reception，并在插入前重验 latest。
     *
     * <p>若同一接收键已存在相同来源事实，则说明并发请求已经完成接收，当前提交无需
     * 覆盖人工信息；若来源不同则以版本冲突拒绝。首次插入必须仍绑定当前上游聚合。</p>
     */
    public boolean requiresPersistenceAgainst(
            HandoffReception persisted, PhaseHandoff latestHandoff) {
        if (persisted != null) {
            requireSameSource(persisted);
            return false;
        }
        if (latestHandoff == null
                || !workbenchId.equals(latestHandoff.getWorkbenchId())
                || sourcePhase != latestHandoff.getSourcePhase()) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        requireLatest(
                latestHandoff.getVersion(), latestHandoff.getContentHash());
        return true;
    }

    private void requireSameSource(HandoffReception persisted) {
        if (!workbenchId.equals(persisted.workbenchId)
                || targetPhase != persisted.targetPhase
                || sourcePhase != persisted.sourcePhase) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        if (sourceVersion != persisted.sourceVersion
                || !sourceHash.equals(persisted.sourceHash)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "handoff reception already binds a different source");
        }
    }
}
