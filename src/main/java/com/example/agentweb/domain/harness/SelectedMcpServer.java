package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Snapshot 中实际挂载的 MCP Server 配置，不包含 Secret 明文。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class SelectedMcpServer {

    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private final String id;
    private final String version;
    private final List<String> command;
    private final List<McpCapability> capabilities;
    private final List<McpSecretReference> secretReferences;
    private final boolean required;
    private final List<String> enabledToolNames;
    private final List<String> disabledToolNames;
    private final int startupTimeoutSeconds;
    private final int toolTimeoutSeconds;
    private final String configurationHash;

    public SelectedMcpServer(McpServerDefinition definition) {
        this(definition, true);
    }

    public SelectedMcpServer(McpServerDefinition definition, boolean required) {
        if (definition == null) {
            throw new IllegalArgumentException("selected MCP definition must not be null");
        }
        this.id = definition.getId();
        this.version = definition.getVersion();
        this.command = immutable(definition.getCommand());
        this.capabilities = immutable(definition.getCapabilities());
        this.secretReferences = immutable(definition.getSecretReferences());
        this.required = required;
        this.enabledToolNames = toolNames(definition.enabledReadToolNames(), "enabled MCP tool");
        this.disabledToolNames = toolNames(definition.disabledWriteToolNames(), "disabled MCP tool");
        if (this.enabledToolNames.isEmpty()) {
            throw new IllegalArgumentException("selected MCP server must enable at least one tool");
        }
        this.startupTimeoutSeconds = definition.getStartupTimeoutSeconds();
        this.toolTimeoutSeconds = definition.getToolTimeoutSeconds();
        this.configurationHash = definition.getConfigurationHash();
    }

    public SelectedMcpServer(String id, String version, List<String> command,
                             List<McpCapability> capabilities,
                             List<McpSecretReference> secretReferences, int timeoutSeconds,
                             String configurationHash) {
        this(new McpServerDefinition(id, version, "restored MCP server",
                java.util.EnumSet.allOf(HarnessStage.class),
                java.util.EnumSet.allOf(AgentRuntime.class), command, capabilities,
                secretReferences, timeoutSeconds, configurationHash));
    }

    public SelectedMcpServer(String id, String version, List<String> command,
                             List<McpCapability> capabilities,
                             List<McpSecretReference> secretReferences, boolean required,
                             List<String> enabledToolNames, List<String> disabledToolNames,
                             int startupTimeoutSeconds, int toolTimeoutSeconds,
                             String configurationHash) {
        this.id = DomainText.require(id, "selected MCP server id", 120);
        this.version = DomainText.require(version, "selected MCP server version", 60);
        this.command = immutable(command);
        this.capabilities = immutable(capabilities);
        this.secretReferences = immutable(secretReferences);
        this.required = required;
        this.enabledToolNames = toolNames(enabledToolNames, "enabled MCP tool");
        this.disabledToolNames = toolNames(disabledToolNames, "disabled MCP tool");
        if (this.enabledToolNames.isEmpty()) {
            throw new IllegalArgumentException("selected MCP server must enable at least one tool");
        }
        if (!Collections.disjoint(this.enabledToolNames, this.disabledToolNames)) {
            throw new IllegalArgumentException("enabled and disabled MCP tools must not overlap");
        }
        this.startupTimeoutSeconds = timeout(startupTimeoutSeconds, "MCP startup timeout");
        this.toolTimeoutSeconds = timeout(toolTimeoutSeconds, "MCP tool timeout");
        this.configurationHash = DomainText.requireSha256(
                configurationHash, "selected MCP configuration hash");
    }

    private <T> List<T> immutable(List<T> values) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException("selected MCP values must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    private List<String> toolNames(List<String> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " list must not be null or contain null");
        }
        Set<String> sorted = new TreeSet<String>();
        for (String value : values) {
            sorted.add(DomainText.require(value, name, 160));
        }
        return Collections.unmodifiableList(new ArrayList<String>(sorted));
    }

    private int timeout(int seconds, String name) {
        if (seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(name + " seconds must be between 1 and 3600");
        }
        return seconds;
    }
}
