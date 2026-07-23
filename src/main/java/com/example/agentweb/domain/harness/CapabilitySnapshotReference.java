package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * HarnessRun 绑定 Snapshot 所需的最小不可变引用。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilitySnapshotReference {

    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final String snapshotHash;
    private final String promptHash;
    private final Set<String> selectedMcpServerIds;

    public CapabilitySnapshotReference(String runId, HarnessStage stage, int attemptNumber,
                                       String snapshotHash, String promptHash,
                                       Set<String> selectedMcpServerIds) {
        this.runId = DomainText.require(runId, "snapshot reference run id", 128);
        if (stage == null || attemptNumber < 1) {
            throw new IllegalArgumentException("snapshot reference stage and positive attempt are required");
        }
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.snapshotHash = DomainText.requireSha256(snapshotHash, "snapshot reference hash");
        this.promptHash = DomainText.requireSha256(promptHash, "snapshot reference prompt hash");
        if (selectedMcpServerIds == null || selectedMcpServerIds.contains(null)) {
            throw new IllegalArgumentException("snapshot MCP ids must not be null or contain null");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String id : selectedMcpServerIds) {
            copy.add(DomainText.require(id, "snapshot MCP id", 120));
        }
        this.selectedMcpServerIds = Collections.unmodifiableSet(copy);
    }

    public static CapabilitySnapshotReference from(CapabilitySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("capability snapshot must not be null");
        }
        if (!CapabilitySnapshot.SCHEMA_M3_1.equals(snapshot.getSchemaVersion())) {
            throw new IllegalHarnessTransitionException(
                    "runtime execution requires a complete M3.1 capability snapshot");
        }
        Set<String> serverIds = new LinkedHashSet<String>();
        for (SelectedMcpServer server : snapshot.getSelectedMcpServers()) {
            serverIds.add(server.getId());
        }
        return new CapabilitySnapshotReference(snapshot.getRunId(), snapshot.getStage(),
                snapshot.getAttemptNumber(), snapshot.getSnapshotHash(), snapshot.getPromptHash(), serverIds);
    }
}
