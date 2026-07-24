package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.SelectedMcpServer;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Snapshot 到 Runtime Adapter 的不可变执行规格，不包含 Secret 明文。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class AgentExecutionSpec {

    private final String executionId;
    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final AgentRuntime runtime;
    private final String workingDir;
    private final String prompt;
    private final String snapshotHash;
    private final String promptHash;
    private final List<SelectedMcpServer> selectedMcpServers;
    private final RuntimeEnforcementProfile enforcementProfile;
    private final WorkspaceRuntimeInventory workspaceInventory;
    private final Set<ArtifactType> requiredOutputArtifacts;

    public AgentExecutionSpec(String executionId, String runId, HarnessStage stage,
                              int attemptNumber, AgentRuntime runtime, String workingDir,
                              String prompt, String snapshotHash, String promptHash,
                              List<SelectedMcpServer> selectedMcpServers,
                              RuntimeEnforcementProfile enforcementProfile,
                              WorkspaceRuntimeInventory workspaceInventory,
                              Set<ArtifactType> requiredOutputArtifacts) {
        if (executionId == null || executionId.trim().isEmpty()
                || runId == null || runId.trim().isEmpty()
                || stage == null || attemptNumber < 1 || runtime == null
                || workingDir == null || workingDir.trim().isEmpty()
                || prompt == null || prompt.trim().isEmpty()
                || snapshotHash == null || promptHash == null
                || selectedMcpServers == null || selectedMcpServers.contains(null)
                || enforcementProfile == null || workspaceInventory == null
                || requiredOutputArtifacts == null || requiredOutputArtifacts.isEmpty()
                || requiredOutputArtifacts.contains(null)) {
            throw new IllegalArgumentException("agent execution spec is incomplete");
        }
        this.executionId = executionId.trim();
        this.runId = runId.trim();
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.runtime = runtime;
        this.workingDir = workingDir;
        this.prompt = prompt;
        this.snapshotHash = snapshotHash;
        this.promptHash = promptHash;
        this.selectedMcpServers = Collections.unmodifiableList(
                new ArrayList<SelectedMcpServer>(selectedMcpServers));
        this.enforcementProfile = enforcementProfile;
        this.workspaceInventory = workspaceInventory;
        this.requiredOutputArtifacts = Collections.unmodifiableSet(
                new LinkedHashSet<ArtifactType>(requiredOutputArtifacts));
    }
}
