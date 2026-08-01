package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 管理员可信 Catalog 中的 MCP Server 定义。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class McpServerDefinition {

    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private final String id;
    private final String version;
    private final String description;
    private final Set<String> applicableUseCases;
    private final Set<String> compatibleRuntimes;
    private final List<String> command;
    private final List<McpCapability> capabilities;
    private final List<McpSecretReference> secretReferences;
    private final int startupTimeoutSeconds;
    private final int toolTimeoutSeconds;
    private final String configurationHash;

    public McpServerDefinition(String id, String version, String description,
                               Set<String> applicableUseCases, Set<String> compatibleRuntimes,
                               List<String> command, List<McpCapability> capabilities,
                               List<McpSecretReference> secretReferences,
                               int startupTimeoutSeconds, int toolTimeoutSeconds,
                               String configurationHash) {
        this.id = DomainText.require(id, "MCP server id", 120);
        this.version = DomainText.require(version, "MCP server version", 60);
        this.description = DomainText.require(description, "MCP server description", 500);
        this.applicableUseCases = strings(applicableUseCases, "MCP use cases", 120);
        this.compatibleRuntimes = strings(compatibleRuntimes, "MCP runtimes", 120);
        this.command = strings(command, "MCP command");
        this.capabilities = values(capabilities, "MCP capabilities");
        this.secretReferences = values(secretReferences, "MCP secret references");
        this.startupTimeoutSeconds = timeout(startupTimeoutSeconds, "MCP startup timeout");
        this.toolTimeoutSeconds = timeout(toolTimeoutSeconds, "MCP tool timeout");
        this.configurationHash = DomainText.requireSha256(
                configurationHash, "MCP configuration hash");
    }

    public McpServerDefinition(String id, String version, String description,
                               Set<String> applicableUseCases, Set<String> compatibleRuntimes,
                               List<String> command, List<McpCapability> capabilities,
                               List<McpSecretReference> secretReferences, int timeoutSeconds,
                               String configurationHash) {
        this(id, version, description, applicableUseCases, compatibleRuntimes, command, capabilities,
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

    public McpToolAuthorization authorizeTools(CapabilityAccess maximumAccess) {
        if (maximumAccess == null || maximumAccess == CapabilityAccess.EXECUTE) {
            throw new IllegalArgumentException(
                    "MCP Tool authorization requires READ or WRITE access");
        }
        Set<String> enabled = new TreeSet<String>();
        Set<String> disabled = new TreeSet<String>();
        for (McpCapability capability : capabilities) {
            if (capability.getType() != McpCapabilityType.TOOL) {
                throw new IllegalArgumentException(
                        "MCP Resource capability cannot be enforced as a Tool");
            }
            if (capability.getAccess() == CapabilityAccess.READ
                    || maximumAccess == CapabilityAccess.WRITE) {
                enabled.add(capability.getId());
            } else {
                disabled.add(capability.getId());
            }
        }
        return new McpToolAuthorization(
                new ArrayList<String>(enabled), new ArrayList<String>(disabled));
    }

    private Set<String> strings(Set<String> source, String name, int maxLength) {
        if (source == null || source.isEmpty() || source.contains(null)) {
            throw new IllegalArgumentException(name + " must not be empty or contain null");
        }
        Set<String> values = new TreeSet<String>();
        for (String value : source) {
            values.add(DomainText.require(value, name, maxLength)
                    .toUpperCase(java.util.Locale.ROOT));
        }
        return Collections.unmodifiableSet(values);
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
