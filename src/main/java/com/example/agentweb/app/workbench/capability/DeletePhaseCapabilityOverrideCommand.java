package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.Objects;

/**
 * 按乐观版本删除 Override 并恢复默认 Profile 的应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DeletePhaseCapabilityOverrideCommand {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final long expectedVersion;

    public DeletePhaseCapabilityOverrideCommand(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            long expectedVersion) {
        this.workbenchId = Objects.requireNonNull(workbenchId, "workbenchId");
        this.phase = Objects.requireNonNull(phase, "phase");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "capability override expected version must not be negative");
        }
        this.expectedVersion = expectedVersion;
    }
}
