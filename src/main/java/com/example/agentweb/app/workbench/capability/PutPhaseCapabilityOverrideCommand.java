package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Owner 侧以最终 optional 选择保存 Phase Capability Override 的命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PutPhaseCapabilityOverrideCommand {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final long expectedVersion;
    private final List<String> optionalSkillIds;
    private final List<String> optionalMcpServerIds;
    private final String additionalRule;

    public PutPhaseCapabilityOverrideCommand(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            long expectedVersion, List<String> optionalSkillIds,
            List<String> optionalMcpServerIds, String additionalRule) {
        this.workbenchId = Objects.requireNonNull(
                workbenchId, "workbenchId");
        this.phase = Objects.requireNonNull(phase, "phase");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "expected capability override version must not be negative");
        }
        this.expectedVersion = expectedVersion;
        this.optionalSkillIds = immutable(
                optionalSkillIds, "optionalSkillIds");
        this.optionalMcpServerIds = immutable(
                optionalMcpServerIds, "optionalMcpServerIds");
        this.additionalRule = additionalRule;
    }

    private static List<String> immutable(
            List<String> values, String name) {
        Objects.requireNonNull(values, name);
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
