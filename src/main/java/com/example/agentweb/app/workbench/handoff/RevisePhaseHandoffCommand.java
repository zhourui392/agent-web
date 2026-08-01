package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.Objects;

/**
 * 按乐观版本修订阶段 Handoff 的应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RevisePhaseHandoffCommand {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase sourcePhase;
    private final long expectedVersion;
    private final PhaseHandoffContentCommand content;

    public RevisePhaseHandoffCommand(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            long expectedVersion, PhaseHandoffContentCommand content) {
        this.workbenchId = Objects.requireNonNull(workbenchId, "workbenchId");
        this.sourcePhase = Objects.requireNonNull(sourcePhase, "sourcePhase");
        this.expectedVersion = expectedVersion;
        this.content = Objects.requireNonNull(content, "content");
    }
}
