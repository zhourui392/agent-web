package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * 不含 acceptedBy 的 Handoff Reception 公开投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffReceptionProjection {

    private final WorkbenchPhase sourcePhase;
    private final long sourceVersion;
    private final String sourceHash;
    private final long acceptedAt;

    public HandoffReceptionProjection(
            WorkbenchPhase sourcePhase, long sourceVersion,
            String sourceHash, long acceptedAt) {
        this.sourcePhase = sourcePhase;
        this.sourceVersion = sourceVersion;
        this.sourceHash = sourceHash;
        this.acceptedAt = acceptedAt;
    }

    public static HandoffReceptionProjection from(
            HandoffReception reception) {
        if (reception == null) {
            throw new IllegalArgumentException("handoff reception is required");
        }
        return new HandoffReceptionProjection(
                reception.getSourcePhase(), reception.getSourceVersion(),
                reception.getSourceHash(),
                reception.getAcceptedAt().toEpochMilli());
    }
}
