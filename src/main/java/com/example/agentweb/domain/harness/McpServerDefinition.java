package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 管理员可信 Catalog 中的 MCP Server 定义。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class McpServerDefinition {

    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private final String id;
    private final String version;
    private final String description;
    private final Set<HarnessStage> applicableStages;
    private final Set<AgentRuntime> runtimes;
    private final List<String> command;
    private final List<McpCapability> capabilities;
    private final List<McpSecretReference> secretReferences;
    private final int startupTimeoutSeconds;
    private final int toolTimeoutSeconds;
    private final String configurationHash;

    public McpServerDefinition(String id, String version, String description,
                               Set<HarnessStage> applicableStages, Set<AgentRuntime> runtimes,
                               List<String> command, List<McpCapability> capabilities,
                               List<McpSecretReference> secretReferences,
                               int startupTimeoutSeconds, int toolTimeoutSeconds,
                               String configurationHash) {
        this.id = DomainText.require(id, "MCP server id", 120);
        this.version = DomainText.require(version, "MCP server version", 60);
        this.description = DomainText.require(description, "MCP server description", 500);
        this.applicableStages = enumSet(applicableStages, HarnessStage.class, "MCP stages");
        this.runtimes = enumSet(runtimes, AgentRuntime.class, "MCP runtimes");
        this.command = strings(command, "MCP command");
        this.capabilities = values(capabilities, "MCP capabilities");
        this.secretReferences = values(secretReferences, "MCP secret references");
        this.startupTimeoutSeconds = timeout(startupTimeoutSeconds, "MCP startup timeout");
        this.toolTimeoutSeconds = timeout(toolTimeoutSeconds, "MCP tool timeout");
        this.configurationHash = DomainText.requireSha256(
                configurationHash, "MCP configuration hash");
    }

    public McpServerDefinition(String id, String version, String description,
                               Set<HarnessStage> applicableStages, Set<AgentRuntime> runtimes,
                               List<String> command, List<McpCapability> capabilities,
                               List<McpSecretReference> secretReferences, int timeoutSeconds,
                               String configurationHash) {
        this(id, version, description, applicableStages, runtimes, command, capabilities,
                secretReferences, timeoutSeconds, timeoutSeconds, configurationHash);
    }

    public boolean hasUnsupportedResourceCapability() {
        for (McpCapability capability : capabilities) {
            if (capability.getType() != McpCapabilityType.TOOL) {
                return true;
            }
        }
        return false;
    }

    public List<String> enabledReadToolNames() {
        Set<String> names = new TreeSet<String>();
        for (McpCapability capability : capabilities) {
            if (capability.getType() == McpCapabilityType.TOOL
                    && capability.getAccess() == CapabilityAccess.READ) {
                names.add(capability.getId());
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    public List<String> disabledWriteToolNames() {
        Set<String> names = new TreeSet<String>();
        for (McpCapability capability : capabilities) {
            if (capability.getType() == McpCapabilityType.TOOL
                    && capability.getAccess() == CapabilityAccess.WRITE) {
                names.add(capability.getId());
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    private <E extends Enum<E>> Set<E> enumSet(Set<E> source, Class<E> type, String name) {
        if (source == null || source.isEmpty() || source.contains(null)) {
            throw new IllegalArgumentException(name + " must not be empty or contain null");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(source));
    }

    private List<String> strings(List<String> source, String name) {
        if (source == null || source.isEmpty() || source.contains(null)) {
            throw new IllegalArgumentException(name + " must not be empty or contain null");
        }
        List<String> copy = new ArrayList<String>();
        for (String value : source) {
            copy.add(DomainText.require(value, name, 1000));
        }
        return Collections.unmodifiableList(copy);
    }

    private <T> List<T> values(List<T> source, String name) {
        if (source == null || source.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    private int timeout(int seconds, String name) {
        if (seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(name + " seconds must be between 1 and 3600");
        }
        return seconds;
    }
}
