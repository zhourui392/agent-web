package com.example.agentweb.interfaces.workbench.admin.dto;

import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.SkillCatalogDirectory;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Workbench Capability Source 管理请求。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@Setter
public final class CapabilitySourceConfigurationRequest {

    private List<CommandCatalogDirectoryRequest> commandCatalogDirectories;
    private List<SkillCatalogDirectoryRequest> skillCatalogDirectories;
    private JsonNode mcpConfiguration;

    public List<CommandCatalogDirectory> toCommandCatalogDirectories() {
        requireLists();
        List<CommandCatalogDirectory> directories =
                new ArrayList<CommandCatalogDirectory>(commandCatalogDirectories.size());
        for (CommandCatalogDirectoryRequest directory : commandCatalogDirectories) {
            if (directory == null) {
                throw new IllegalArgumentException(
                        "command catalog directory request must not be null");
            }
            directories.add(CommandCatalogDirectory.create(
                    directory.getDirectoryIdentifier(),
                    directory.getAbsoluteDirectory(), directory.isEnabled()));
        }
        return directories;
    }

    public List<SkillCatalogDirectory> toSkillCatalogDirectories() {
        requireLists();
        List<SkillCatalogDirectory> directories =
                new ArrayList<SkillCatalogDirectory>(skillCatalogDirectories.size());
        for (SkillCatalogDirectoryRequest directory : skillCatalogDirectories) {
            if (directory == null || directory.getTrustSource() == null) {
                throw new IllegalArgumentException(
                        "skill catalog directory and trust source are required");
            }
            directories.add(SkillCatalogDirectory.create(
                    directory.getDirectoryIdentifier(),
                    directory.getAbsoluteDirectory(),
                    SkillTrustSource.valueOf(directory.getTrustSource()),
                    directory.isEnabled()));
        }
        return directories;
    }

    private void requireLists() {
        if (commandCatalogDirectories == null || skillCatalogDirectories == null) {
            throw new IllegalArgumentException(
                    "command and skill catalog directory lists are required");
        }
    }

    /**
     * Command Catalog 目录请求。
     *
     * @author alex
     * @since 2026-08-05
     */
    @Getter
    @Setter
    public static final class CommandCatalogDirectoryRequest {
        private String directoryIdentifier;
        private String absoluteDirectory;
        private boolean enabled;
    }

    /**
     * Skill Catalog 目录请求。
     *
     * @author alex
     * @since 2026-08-05
     */
    @Getter
    @Setter
    public static final class SkillCatalogDirectoryRequest {
        private String directoryIdentifier;
        private String absoluteDirectory;
        private String trustSource;
        private boolean enabled;
    }
}
