package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * Run Snapshot 固化的上游 Handoff 版本与 Hash。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffSnapshotReference {

    private final WorkbenchPhase sourcePhase;
    private final long sourceVersion;
    private final String sourceHash;

    private HandoffSnapshotReference(WorkbenchPhase sourcePhase,
                                     long sourceVersion, String sourceHash) {
        if (sourcePhase == null) {
            throw new IllegalArgumentException("handoff source phase must not be null");
        }
        if (sourceVersion < 0L) {
            throw new IllegalArgumentException(
                    "handoff source version must not be negative");
        }
        this.sourcePhase = sourcePhase;
        this.sourceVersion = sourceVersion;
        this.sourceHash = DomainText.requireSha256(sourceHash, "handoff source hash");
    }

    public static HandoffSnapshotReference of(
            WorkbenchPhase sourcePhase, long sourceVersion, String sourceHash) {
        return new HandoffSnapshotReference(sourcePhase, sourceVersion, sourceHash);
    }
}
