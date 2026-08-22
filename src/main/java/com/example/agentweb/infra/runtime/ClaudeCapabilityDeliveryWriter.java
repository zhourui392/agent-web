package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.ModeRuntimeDelivery;
import com.example.agentweb.domain.capability.McpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将单次 Run 已重验的能力物化为 Claude CLI 的下发产物：
 * {@code --mcp-config} JSON 与 {@code --plugin-dir} 插件目录。
 *
 * <p>MCP secret 以 {@code ${VAR}} 占位写入 JSON，实际值由进程 env 注入机制
 * （{@link RuntimeCapabilityMaterialization#applySecretEnvironment}）在启动时提供，
 * 任何环节不落盘明文。产物全部位于 capabilityRoot 内，随既有清理路径回收。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ClaudeCapabilityDeliveryWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SAFE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final String PLUGIN_NAME = "agent-mode";

    private final Path capabilityRoot;

    public ClaudeCapabilityDeliveryWriter(Path capabilityRoot) {
        this.capabilityRoot = capabilityRoot;
    }

    /**
     * 物化并返回下发事实。
     *
     * @param capabilities 已重验的 Skill/MCP 物化结果（skill 包已落盘）
     * @param delivery     模式命令内容与提示词/权限（commands 为空且无 MCP 时可能全部跳过）
     */
    public ClaudeDelivery write(RuntimeCapabilityMaterialization capabilities,
                                ModeRuntimeDelivery delivery) throws IOException {
        Files.createDirectories(capabilityRoot);
        String mcpConfigPath = null;
        String pluginDir = null;
        if (!capabilities.getMcpServers().isEmpty()) {
            mcpConfigPath = writeMcpConfig(capabilities).toString();
        }
        boolean hasCommands = delivery != null && !delivery.getCommands().isEmpty();
        boolean hasSkills = !capabilities.getSkills().isEmpty();
        if (hasCommands || hasSkills) {
            // --plugin-dir 指向插件根（内含 .claude-plugin/plugin.json），非其父目录
            pluginDir = writePluginDir(capabilities, delivery).toString();
        }
        return new ClaudeDelivery(mcpConfigPath, pluginDir);
    }

    private Path writeMcpConfig(RuntimeCapabilityMaterialization capabilities)
            throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        for (RuntimeCapabilityMaterialization.MaterializedMcpServer server
                : capabilities.getMcpServers()) {
            ObjectNode node = servers.putObject(requireName(server.getId()));
            Map<String, String> envPlaceholders = new LinkedHashMap<String, String>();
            for (String variable : server.getSecretEnvironmentVariables()) {
                envPlaceholders.put(variable, "${" + variable + "}");
            }
            if (server.getTransport() == McpTransport.STREAMABLE_HTTP) {
                node.put("type", "http");
                node.put("url", server.getEndpoint());
                if (!envPlaceholders.isEmpty()) {
                    // HTTP secret 以 Bearer 注入，占位同样由进程 env 展开
                    node.putObject("headers")
                            .put("Authorization", "Bearer "
                                    + envPlaceholders.values().iterator().next());
                }
            } else {
                node.put("type", "stdio");
                node.put("command", server.getCommand().get(0));
                ArrayNode args = node.putArray("args");
                for (int index = 1; index < server.getCommand().size(); index++) {
                    args.add(server.getCommand().get(index));
                }
                if (!envPlaceholders.isEmpty()) {
                    ObjectNode env = node.putObject("env");
                    envPlaceholders.forEach(env::put);
                }
            }
        }
        Path file = capabilityRoot.resolve("mcp.json");
        Files.writeString(file, OBJECT_MAPPER.writeValueAsString(root),
                StandardCharsets.UTF_8);
        return file;
    }

    /** @return 插件根目录（含 .claude-plugin/plugin.json），直接作为 --plugin-dir 值 */
    private Path writePluginDir(RuntimeCapabilityMaterialization capabilities,
                                ModeRuntimeDelivery delivery) throws IOException {
        Path pluginRoot = capabilityRoot.resolve("plugin");
        Path plugin = pluginRoot.resolve(PLUGIN_NAME);
        ObjectNode manifest = OBJECT_MAPPER.createObjectNode();
        manifest.put("name", PLUGIN_NAME);
        Files.createDirectories(plugin.resolve(".claude-plugin"));
        Files.writeString(plugin.resolve(".claude-plugin").resolve("plugin.json"),
                OBJECT_MAPPER.writeValueAsString(manifest), StandardCharsets.UTF_8);
        if (delivery != null && !delivery.getCommands().isEmpty()) {
            Path commands = plugin.resolve("commands");
            Files.createDirectories(commands);
            for (ModeRuntimeDelivery.CommandPayload command : delivery.getCommands()) {
                Path target = commands.resolve(requireName(command.getIdentifier()) + ".md");
                Files.writeString(target, command.getContentMarkdown(),
                        StandardCharsets.UTF_8);
            }
        }
        if (!capabilities.getSkills().isEmpty()) {
            Path skills = plugin.resolve("skills");
            Files.createDirectories(skills);
            // 物化器已将 skill 包写到 capabilityRoot/skills/<id>/<version>/，整目录复刻进插件
            for (RuntimeCapabilityMaterialization.MaterializedSkill skill
                    : capabilities.getSkills()) {
                Path packageDir = skill.getEntryPath().getParent();
                if (packageDir == null || !packageDir.startsWith(capabilityRoot)) {
                    throw new IllegalStateException(
                            "materialized skill package escapes capability root");
                }
                copyRecursively(packageDir, skills.resolve(requireName(skill.getId())));
            }
        }
        return plugin;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path entry : stream.filter(p -> !p.equals(source))
                    .sorted().toList()) {
                Path relative = source.relativize(entry);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String requireName(String value) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalStateException(
                    "capability name is unsafe for Claude delivery: " + value);
        }
        return value;
    }

    /**
     * Claude 下发产物事实。
     */
    public record ClaudeDelivery(String mcpConfigPath, String pluginDirPath) {
    }
}
