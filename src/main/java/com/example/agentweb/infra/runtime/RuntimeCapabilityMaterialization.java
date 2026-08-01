package com.example.agentweb.infra.runtime;

import lombok.Getter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Runtime 已重验并物化的非持久化 Skill/MCP Provider 配置。
 *
 * <p>Secret 仅以可清零字符数组保留，公开视图只暴露环境变量名。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCapabilityMaterialization implements AutoCloseable {

    private final String bindingHash;
    private final List<MaterializedSkill> skills;
    private final List<MaterializedMcpServer> mcpServers;
    private final Map<String, char[]> secrets;
    private boolean closed;

    RuntimeCapabilityMaterialization(
            String bindingHash, List<MaterializedSkill> skills,
            List<MaterializedMcpServer> mcpServers,
            Map<String, char[]> secrets) {
        if (bindingHash == null || bindingHash.trim().isEmpty()
                || skills == null || skills.contains(null)
                || mcpServers == null || mcpServers.contains(null)
                || secrets == null || secrets.containsKey(null)
                || secrets.containsValue(null)) {
            throw new IllegalArgumentException(
                    "runtime capability materialization facts must be complete");
        }
        this.bindingHash = bindingHash;
        this.skills = Collections.unmodifiableList(
                new ArrayList<MaterializedSkill>(skills));
        this.mcpServers = Collections.unmodifiableList(
                new ArrayList<MaterializedMcpServer>(mcpServers));
        this.secrets = copySecrets(secrets);
    }

    static RuntimeCapabilityMaterialization empty(String bindingHash) {
        return new RuntimeCapabilityMaterialization(
                bindingHash, Collections.<MaterializedSkill>emptyList(),
                Collections.<MaterializedMcpServer>emptyList(),
                Collections.<String, char[]>emptyMap());
    }

    public String getBindingHash() {
        return bindingHash;
    }

    public List<MaterializedSkill> getSkills() {
        return skills;
    }

    public List<MaterializedMcpServer> getMcpServers() {
        return mcpServers;
    }

    public synchronized void applySecretEnvironment(Map<String, String> environment) {
        requireOpen();
        if (environment == null) {
            throw new IllegalArgumentException("runtime environment must not be null");
        }
        for (Map.Entry<String, char[]> entry : secrets.entrySet()) {
            environment.put(entry.getKey(), new String(entry.getValue()));
        }
    }

    public synchronized void clearSecretEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return;
        }
        for (String name : secrets.keySet()) {
            environment.remove(name);
        }
    }

    public synchronized String redact(
            String value, RuntimeOutputRedactor redactor) {
        requireOpen();
        if (redactor == null) {
            throw new IllegalArgumentException("runtime output redactor must not be null");
        }
        List<String> values = new ArrayList<String>();
        for (char[] secret : secrets.values()) {
            values.add(new String(secret));
        }
        return redactor.redactSecrets(value, values);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        for (char[] value : secrets.values()) {
            Arrays.fill(value, '\0');
        }
        closed = true;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public String toString() {
        return "RuntimeCapabilityMaterialization{bindingHash='" + bindingHash
                + "', skills=" + skills.size()
                + ", mcpServers=" + mcpServers.size()
                + ", secretReferences=" + secrets.keySet() + '}';
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "runtime capability materialization is already closed");
        }
    }

    private Map<String, char[]> copySecrets(Map<String, char[]> source) {
        Map<String, char[]> copy = new LinkedHashMap<String, char[]>();
        for (Map.Entry<String, char[]> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return copy;
    }

    /**
     * 已写入单次执行目录的 Skill 入口。
     */
    @Getter
    public static final class MaterializedSkill {

        private final String id;
        private final String version;
        private final Path entryPath;

        MaterializedSkill(String id, String version, Path entryPath) {
            this.id = id;
            this.version = version;
            this.entryPath = entryPath;
        }
    }

    /**
     * 已从 exact Catalog 定义收敛出的单次 MCP STDIO 配置。
     */
    @Getter
    public static final class MaterializedMcpServer {

        private final String id;
        private final String version;
        private final List<String> command;
        private final List<String> secretEnvironmentVariables;
        private final boolean required;
        private final int startupTimeoutSeconds;
        private final int toolTimeoutSeconds;
        private final List<String> enabledToolNames;
        private final List<String> disabledToolNames;

        MaterializedMcpServer(
                String id, String version, List<String> command,
                List<String> secretEnvironmentVariables, boolean required,
                int startupTimeoutSeconds, int toolTimeoutSeconds,
                List<String> enabledToolNames, List<String> disabledToolNames) {
            this.id = id;
            this.version = version;
            this.command = immutable(command);
            this.secretEnvironmentVariables = immutable(secretEnvironmentVariables);
            this.required = required;
            this.startupTimeoutSeconds = startupTimeoutSeconds;
            this.toolTimeoutSeconds = toolTimeoutSeconds;
            this.enabledToolNames = immutable(enabledToolNames);
            this.disabledToolNames = immutable(disabledToolNames);
        }

        private List<String> immutable(List<String> values) {
            return Collections.unmodifiableList(new ArrayList<String>(values));
        }
    }
}
