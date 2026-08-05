package com.example.agentweb.infra.capability;

import com.example.agentweb.config.capability.CapabilityCatalogProperties;
import com.example.agentweb.domain.capability.CapabilityConfigurationEditor;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.domain.capability.CapabilitySourceConfigurationRepository;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 数据库 Capability Source 驱动运行时 Catalog 的测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class ConfiguredCapabilityCatalogTest {

    @TempDir
    Path tempDir;

    @Mock
    private CapabilitySourceConfigurationRepository repository;

    @Test
    void should_DiscoverCommandsFromStoredConfiguration_When_ConfigurationExists()
            throws IOException {
        // Given
        Path commands = Files.createDirectories(tempDir.resolve("commands"));
        Files.writeString(commands.resolve("review.md"), "---\n"
                + "identifier: review\nversion: 1\ndisplayName: Review\n"
                + "description: Review code\nargumentHint: <target>\n---\n"
                + "Review $ARGUMENTS\n");
        when(repository.find()).thenReturn(Optional.of(configuration(
                Collections.singletonList(CommandCatalogDirectory.create(
                        "commands", commands.toString(), true)), emptyMcp())));
        ConfiguredCommandCatalog catalog = new ConfiguredCommandCatalog(
                repository, Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"),
                        ZoneOffset.UTC));

        // When
        List<CommandDefinition> definitions = catalog.discover();

        // Then
        assertEquals(1, definitions.size());
        assertEquals("review", definitions.get(0).getIdentifier());
    }

    @Test
    void should_UseStoredMcpJsonAndSkillDirectories_When_ConfigurationExists() {
        // Given
        String mcp = "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[{"
                + "\"identifier\":\"remote\",\"version\":\"1\","
                + "\"displayName\":\"Remote\",\"description\":\"Remote MCP\","
                + "\"transport\":\"STREAMABLE_HTTP\","
                + "\"endpoint\":\"https://mcp.example.test/api\","
                + "\"environmentVariables\":{},\"access\":\"READ_ONLY\","
                + "\"compatibleRuntimes\":[\"CODEX\"]}]}";
        when(repository.find()).thenReturn(Optional.of(configuration(
                Collections.emptyList(), mcp)));
        CapabilityCatalogProperties properties = new CapabilityCatalogProperties();
        properties.setPlatformSkillRoot(tempDir.resolve("missing-skills").toString());
        properties.setMcpServerRoot(tempDir.resolve("missing-mcp").toString());

        // When
        FileSystemSkillCatalog skillCatalog = new FileSystemSkillCatalog(
                properties, repository);
        FileSystemMcpServerCatalog mcpCatalog = new FileSystemMcpServerCatalog(
                properties, repository, new ObjectMapper());

        // Then
        assertEquals(0, skillCatalog.discover().size());
        assertEquals(McpTransport.STREAMABLE_HTTP,
                mcpCatalog.discover().get(0).getTransport());
    }

    private CapabilitySourceConfiguration configuration(
            List<CommandCatalogDirectory> commands, String mcp) {
        return CapabilitySourceConfiguration.create(
                commands, Collections.emptyList(), mcp,
                CapabilityConfigurationEditor.create("admin", "Admin"),
                Instant.parse("2026-08-05T08:00:00Z"));
    }

    private String emptyMcp() {
        return "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}";
    }
}
