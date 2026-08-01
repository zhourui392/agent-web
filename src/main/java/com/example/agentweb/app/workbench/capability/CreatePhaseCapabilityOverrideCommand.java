package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.Objects;

/**
 * 首次创建 Phase Capability Override 的应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class CreatePhaseCapabilityOverrideCommand {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final CapabilityOverrideSelection selection;

    public CreatePhaseCapabilityOverrideCommand(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            CapabilityOverrideSelection selection) {
        this.workbenchId = Objects.requireNonNull(workbenchId, "workbenchId");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.selection = Objects.requireNonNull(selection, "selection");
    }
}
