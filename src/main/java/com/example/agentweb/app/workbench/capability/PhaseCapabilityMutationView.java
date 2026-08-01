package com.example.agentweb.app.workbench.capability;

import lombok.Getter;

/**
 * Capability Override 写操作的公开生效证据。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityMutationView {

    private final long version;
    private final String effectiveFrom;
    private final String activeRunSnapshotHash;

    private PhaseCapabilityMutationView(
            long version, String activeRunSnapshotHash) {
        this.version = version;
        this.effectiveFrom = CapabilityOverrideEffectiveFrom.NEXT_RUN.name();
        this.activeRunSnapshotHash = activeRunSnapshotHash;
    }

    public static PhaseCapabilityMutationView nextRun(
            long version, String activeRunSnapshotHash) {
        return new PhaseCapabilityMutationView(
                version, activeRunSnapshotHash);
    }
}
