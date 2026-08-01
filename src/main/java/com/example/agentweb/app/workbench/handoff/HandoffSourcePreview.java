package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * 目标 Phase 默认上游 Handoff 的最新、已接收与 stale 投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffSourcePreview {

    private final WorkbenchPhase targetPhase;
    private final PhaseHandoffProjection latestSource;
    private final HandoffReceptionProjection reception;
    private final PhaseHandoffProjection acceptedSource;
    private final boolean stale;
    private final HandoffDiffSummary diff;

    public HandoffSourcePreview(
            WorkbenchPhase targetPhase,
            PhaseHandoffProjection latestSource,
            HandoffReceptionProjection reception,
            PhaseHandoffProjection acceptedSource,
            boolean stale, HandoffDiffSummary diff) {
        if (targetPhase == null) {
            throw new IllegalArgumentException(
                    "handoff target phase is required");
        }
        this.targetPhase = targetPhase;
        this.latestSource = latestSource;
        this.reception = reception;
        this.acceptedSource = acceptedSource;
        this.stale = stale;
        this.diff = diff;
    }

    public static HandoffSourcePreview withoutDefaultSource(
            WorkbenchPhase targetPhase) {
        return new HandoffSourcePreview(
                targetPhase, null, null, null, false, null);
    }
}
