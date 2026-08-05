package com.example.agentweb.app.capability;

import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.SkillCatalogDirectory;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已完成真实路径、Catalog 和 MCP JSON 验证的来源探测结果。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class CapabilitySourceProbeResult {

    private final List<CommandCatalogDirectory> commandCatalogDirectories;
    private final List<SkillCatalogDirectory> skillCatalogDirectories;
    private final String canonicalMcpConfigurationJson;
    private final List<CapabilityDiscoveryItem> commands;
    private final List<CapabilityDiscoveryItem> skills;
    private final List<CapabilityDiscoveryItem> mcpServers;
    private final List<String> warnings;

    public CapabilitySourceProbeResult(
            List<CommandCatalogDirectory> commandCatalogDirectories,
            List<SkillCatalogDirectory> skillCatalogDirectories,
            String canonicalMcpConfigurationJson,
            List<CapabilityDiscoveryItem> commands,
            List<CapabilityDiscoveryItem> skills,
            List<CapabilityDiscoveryItem> mcpServers,
            List<String> warnings) {
        this.commandCatalogDirectories = immutable(
                commandCatalogDirectories, "command catalog directories");
        this.skillCatalogDirectories = immutable(
                skillCatalogDirectories, "skill catalog directories");
        this.canonicalMcpConfigurationJson = DomainText.require(
                canonicalMcpConfigurationJson, "canonical MCP configuration JSON",
                1024 * 1024);
        this.commands = immutable(commands, "discovered commands");
        this.skills = immutable(skills, "discovered skills");
        this.mcpServers = immutable(mcpServers, "discovered MCP servers");
        this.warnings = immutable(warnings, "capability source warnings");
    }

    private <T> List<T> immutable(List<T> source, String name) {
        if (source == null || source.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }
}
