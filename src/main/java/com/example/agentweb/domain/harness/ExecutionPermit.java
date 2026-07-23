package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * HarnessRun 在聚合内校验当前 Attempt 和 Snapshot 后签发的执行许可。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class ExecutionPermit {

    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final String snapshotHash;
    private final String promptHash;
    private final Set<String> selectedMcpServerIds;

    public ExecutionPermit(String runId, HarnessStage stage, int attemptNumber,
                           String snapshotHash, String promptHash,
                           Set<String> selectedMcpServerIds) {
        CapabilitySnapshotReference reference = new CapabilitySnapshotReference(runId, stage,
                attemptNumber, snapshotHash, promptHash, selectedMcpServerIds);
        this.runId = reference.getRunId();
        this.stage = reference.getStage();
        this.attemptNumber = reference.getAttemptNumber();
        this.snapshotHash = reference.getSnapshotHash();
        this.promptHash = reference.getPromptHash();
        this.selectedMcpServerIds = Collections.unmodifiableSet(
                new LinkedHashSet<String>(reference.getSelectedMcpServerIds()));
    }
}
