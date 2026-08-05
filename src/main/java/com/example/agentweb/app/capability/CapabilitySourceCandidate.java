package com.example.agentweb.app.capability;

import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.SkillCatalogDirectory;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 尚未保存、等待真实来源探测的 Capability Source 候选。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class CapabilitySourceCandidate {

    private final List<CommandCatalogDirectory> commandCatalogDirectories;
    private final List<SkillCatalogDirectory> skillCatalogDirectories;
    private final String mcpConfigurationJson;

    public CapabilitySourceCandidate(
            List<CommandCatalogDirectory> commandCatalogDirectories,
            List<SkillCatalogDirectory> skillCatalogDirectories,
            String mcpConfigurationJson) {
        this.commandCatalogDirectories = immutable(
                commandCatalogDirectories, "command catalog directories");
        this.skillCatalogDirectories = immutable(
                skillCatalogDirectories, "skill catalog directories");
        this.mcpConfigurationJson = DomainText.require(
                mcpConfigurationJson, "MCP configuration JSON", 1024 * 1024);
    }

    private <T> List<T> immutable(List<T> source, String name) {
        if (source == null || source.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }
}
