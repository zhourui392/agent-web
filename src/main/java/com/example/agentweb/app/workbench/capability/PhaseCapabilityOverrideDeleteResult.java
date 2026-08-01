package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.Objects;

/**
 * 恢复默认 Profile 的结果，明确只影响下一轮 Run。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityOverrideDeleteResult {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final CapabilityOverrideEffectiveFrom effectiveFrom;

    private PhaseCapabilityOverrideDeleteResult(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        this.workbenchId = Objects.requireNonNull(workbenchId, "workbenchId");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.effectiveFrom = CapabilityOverrideEffectiveFrom.NEXT_RUN;
    }

    public static PhaseCapabilityOverrideDeleteResult restoredDefault(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        return new PhaseCapabilityOverrideDeleteResult(workbenchId, phase);
    }
}
