package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.McpCapability;
import com.example.agentweb.domain.harness.SelectedMcpServer;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RuntimeExecution 管理 API 的脱敏读视图。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimeExecutionView {

    private final String executionId;
    private final String runId;
    private final String stage;
    private final int attemptNumber;
    private final String runtime;
    private final String runtimeVersion;
    private final String status;
    private final String snapshotHash;
    private final String promptHash;
    private final String terminationReason;
    private final Integer exitCode;
    private final String evidenceReference;
    private final String cleanupStatus;
    private final Instant preparedAt;
    private final Instant startedAt;
    private final Instant cancelRequestedAt;
    private final Instant finishedAt;
    private final List<McpServerView> selectedMcpServers;

    public RuntimeExecutionView(String executionId, String runId, String stage,
                                int attemptNumber, String runtime, String runtimeVersion,
                                String status, String snapshotHash, String promptHash,
                                String terminationReason, Integer exitCode,
                                String evidenceReference, String cleanupStatus,
                                Instant preparedAt, Instant startedAt,
                                Instant cancelRequestedAt, Instant finishedAt,
                                List<SelectedMcpServer> selectedMcpServers) {
        this.executionId = executionId;
        this.runId = runId;
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.runtime = runtime;
        this.runtimeVersion = runtimeVersion;
        this.status = status;
        this.snapshotHash = snapshotHash;
        this.promptHash = promptHash;
        this.terminationReason = terminationReason;
        this.exitCode = exitCode;
        this.evidenceReference = evidenceReference;
        this.cleanupStatus = cleanupStatus;
        this.preparedAt = preparedAt;
        this.startedAt = startedAt;
        this.cancelRequestedAt = cancelRequestedAt;
        this.finishedAt = finishedAt;
        List<McpServerView> views = new ArrayList<McpServerView>();
        for (SelectedMcpServer server : selectedMcpServers) {
            views.add(new McpServerView(server));
        }
        this.selectedMcpServers = Collections.unmodifiableList(views);
    }

    /**
     * 不含启动命令和 Secret Reference 的 MCP 摘要。
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
}
