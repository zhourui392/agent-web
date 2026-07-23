package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityDecision;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.HarnessPromptPart;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpCapability;
import com.example.agentweb.domain.harness.RejectedMcpServer;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.RejectedSkill;
import com.example.agentweb.domain.harness.SelectedMcpServer;
import com.example.agentweb.domain.harness.SnapshotSkill;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理 API 使用的不可变 Capability Snapshot 读视图。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilitySnapshotView {

    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final AgentRuntime runtime;
    private final String environment;
    private final String policyVersion;
    private final String schemaVersion;
    private final String promptPackId;
    private final String promptPackVersion;
    private final String promptPackHash;
    private final Map<String, String> promptResourceHashes;
    private final List<SnapshotSkill> selectedSkills;
    private final List<RejectedSkill> rejectedSkills;
    private final List<CapabilityDecision> capabilityDecisions;
    private final List<McpServerView> selectedMcpServers;
    private final List<RejectedMcpServer> rejectedMcpServers;
    private final RuntimeEnforcementView runtimeEnforcement;
    private final String workspaceRuntimeInventoryHash;
    private final List<HarnessPromptPart> promptParts;
    private final String finalPrompt;
    private final String promptHash;
    private final String snapshotHash;
    private final Instant createdAt;

    public CapabilitySnapshotView(CapabilitySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("capability snapshot must not be null");
        }
        this.runId = snapshot.getRunId();
        this.stage = snapshot.getStage();
        this.attemptNumber = snapshot.getAttemptNumber();
        this.runtime = snapshot.getRuntime();
        this.environment = snapshot.getEnvironment();
        this.policyVersion = snapshot.getPolicyVersion();
        this.schemaVersion = snapshot.getSchemaVersion();
        this.promptPackId = snapshot.getPromptPackId();
        this.promptPackVersion = snapshot.getPromptPackVersion();
        this.promptPackHash = snapshot.getPromptPackHash();
        this.promptResourceHashes = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(snapshot.getPromptResourceHashes()));
        this.selectedSkills = immutable(snapshot.getSelectedSkills());
        this.rejectedSkills = immutable(snapshot.getRejectedSkills());
        this.capabilityDecisions = immutable(snapshot.getCapabilityDecisions());
        List<McpServerView> mcpViews = new ArrayList<McpServerView>();
        for (SelectedMcpServer server : snapshot.getSelectedMcpServers()) {
            mcpViews.add(new McpServerView(server));
        }
        this.selectedMcpServers = immutable(mcpViews);
        this.rejectedMcpServers = immutable(snapshot.getRejectedMcpServers());
        this.runtimeEnforcement = new RuntimeEnforcementView(
                snapshot.getRuntimeEnforcementProfile());
        this.workspaceRuntimeInventoryHash =
                snapshot.getWorkspaceRuntimeInventory().getInventoryHash();
        this.promptParts = immutable(snapshot.getPromptParts());
        this.finalPrompt = snapshot.getFinalPrompt();
        this.promptHash = snapshot.getPromptHash();
        this.snapshotHash = snapshot.getSnapshotHash();
        this.createdAt = snapshot.getCreatedAt();
    }

    private <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    /**
     * API 可见的 MCP 只读配置摘要；不含命令和 Secret Reference。
     *
     * @author zhourui(V33215020)
     * @since 2026-07-23
     */
    @Getter
    public static final class McpServerView {

        private final String id;
        private final String version;
        private final List<McpCapability> capabilities;
        private final String configurationHash;

        private McpServerView(SelectedMcpServer server) {
            this.id = server.getId();
            this.version = server.getVersion();
            this.capabilities = Collections.unmodifiableList(
                    new ArrayList<McpCapability>(server.getCapabilities()));
            this.configurationHash = server.getConfigurationHash();
        }
    }

    /**
     * API 可见的 Runtime 强制能力摘要。
     *
     * @author zhourui(V33215020)
     * @since 2026-07-23
     */
    @Getter
    public static final class RuntimeEnforcementView {

        private final String profileVersion;
        private final String runtimeVersion;
        private final String sandboxMode;
        private final boolean toolAllowDenyEnforced;
        private final boolean userConfigIsolated;
        private final boolean processTreeCancellationEnforced;
        private final String profileHash;

        private RuntimeEnforcementView(RuntimeEnforcementProfile profile) {
            this.profileVersion = profile.getProfileVersion();
            this.runtimeVersion = profile.getRuntimeVersion();
            this.sandboxMode = profile.getSandboxMode();
            this.toolAllowDenyEnforced = profile.isToolAllowDenyEnforced();
            this.userConfigIsolated = profile.isUserConfigIsolated();
            this.processTreeCancellationEnforced =
                    profile.isProcessTreeCancellationEnforced();
            this.profileHash = profile.getProfileHash();
        }
    }
}
