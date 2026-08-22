package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.ModeRuntimeDelivery;
import com.example.agentweb.domain.capability.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claude 能力下发物化回归：mcp.json 结构 + plugin 目录布局。
 *
 * @author alex
 * @since 2026-08-20
 */
class ClaudeCapabilityDeliveryWriterTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesMcpConfigWithEnvPlaceholders() throws Exception {
        RuntimeCapabilityMaterialization capabilities = new RuntimeCapabilityMaterialization(
                "binding-hash",
                java.util.Arrays.asList(),
                java.util.Arrays.asList(new RuntimeCapabilityMaterialization.MaterializedMcpServer(
                        "spike", "1", McpTransport.STDIO,
                        java.util.Arrays.asList("npx", "-y", "spike-server"), "", "",
                        java.util.Arrays.asList("SPIKE_TOKEN"), true, 30, 60,
                        java.util.Arrays.asList(), java.util.Arrays.asList())),
                new java.util.LinkedHashMap<>(java.util.Map.of(
                        "SPIKE_TOKEN", "real-secret".toCharArray())));
        ClaudeCapabilityDeliveryWriter writer =
                new ClaudeCapabilityDeliveryWriter(tempDir);
        ClaudeCapabilityDeliveryWriter.ClaudeDelivery delivery =
                writer.write(capabilities, null);
        JsonNode root = MAPPER.readTree(Files.readString(Path.of(delivery.mcpConfigPath())));
        JsonNode server = root.get("mcpServers").get("spike");
        assertEquals("stdio", server.get("type").asText());
        assertEquals("npx", server.get("command").asText());
        assertEquals("spi", server.get("args").get(1).asText().substring(0, 3));
        // secret 只以 ${VAR} 占位出现, 不落明文
        String fileText = Files.readString(Path.of(delivery.mcpConfigPath()));
        assertTrue(fileText.contains("${SPIKE_TOKEN}"));
        assertFalse(fileText.contains("real-secret"));
        // 无命令时不出 plugin 目录
        assertEquals(null, delivery.pluginDirPath());
        capabilities.close();
    }

    @Test
    void writesPluginLayoutWithCommandsAndSkills() throws Exception {
        Path skillPackage = tempDir.resolve("skills").resolve("review").resolve("1");
        Files.createDirectories(skillPackage);
        Files.writeString(skillPackage.resolve("SKILL.md"), "# review skill");
        RuntimeCapabilityMaterialization capabilities = new RuntimeCapabilityMaterialization(
                "binding-hash",
                java.util.Arrays.asList(new RuntimeCapabilityMaterialization.MaterializedSkill(
                        "review", "1", skillPackage.resolve("SKILL.md"))),
                java.util.Arrays.asList(),
                new java.util.LinkedHashMap<String, char[]>());
        ModeRuntimeDelivery delivery = new ModeRuntimeDelivery(
                "系统提示", "plan",
                List.of(new ModeRuntimeDelivery.CommandPayload(
                        "review-cmd", "# 评审命令\n$ARGUMENTS")));
        ClaudeCapabilityDeliveryWriter.ClaudeDelivery written =
                new ClaudeCapabilityDeliveryWriter(tempDir).write(capabilities, delivery);
        Path plugin = Path.of(written.pluginDirPath());
        assertEquals(tempDir.resolve("plugin").resolve("agent-mode"), plugin);
        String manifest = Files.readString(
                plugin.resolve(".claude-plugin").resolve("plugin.json"));
        assertTrue(manifest.contains("\"agent-mode\""));
        assertEquals("# 评审命令\n$ARGUMENTS",
                Files.readString(plugin.resolve("commands").resolve("review-cmd.md")));
        assertEquals("# review skill",
                Files.readString(plugin.resolve("skills").resolve("review")
                        .resolve("SKILL.md")));
    }

    @Test
    void emptyDeliveryProducesNoArtifacts() throws Exception {
        RuntimeCapabilityMaterialization capabilities =
                RuntimeCapabilityMaterialization.empty("binding-hash");
        ClaudeCapabilityDeliveryWriter.ClaudeDelivery delivery =
                new ClaudeCapabilityDeliveryWriter(tempDir).write(capabilities, null);
        assertEquals(null, delivery.mcpConfigPath());
        assertEquals(null, delivery.pluginDirPath());
    }
}
