package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.Objects;

/**
 * 下游阶段接受指定上游 Handoff 版本的应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AcceptHandoffReceptionCommand {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase targetPhase;
    private final WorkbenchPhase sourcePhase;
    private final long sourceVersion;
    private final String sourceHash;

    public AcceptHandoffReceptionCommand(
            WorkbenchId workbenchId, WorkbenchPhase targetPhase,
            WorkbenchPhase sourcePhase, long sourceVersion, String sourceHash) {
        this.workbenchId = Objects.requireNonNull(workbenchId, "workbenchId");
        this.targetPhase = Objects.requireNonNull(targetPhase, "targetPhase");
        this.sourcePhase = Objects.requireNonNull(sourcePhase, "sourcePhase");
        this.sourceVersion = sourceVersion;
        this.sourceHash = sourceHash;
    }
}
