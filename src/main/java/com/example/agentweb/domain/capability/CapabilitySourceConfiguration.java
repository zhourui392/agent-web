package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Workbench Command、Skill 与 MCP 来源的原子配置聚合。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class CapabilitySourceConfiguration {

    private static final int MAX_DIRECTORIES_PER_CATALOG = 32;
    private static final int MAX_MCP_CONFIGURATION_LENGTH = 1024 * 1024;
    private static final String HASH_SCHEMA = "workbench-capability-source@1";

    private final List<CommandCatalogDirectory> commandCatalogDirectories;
    private final List<SkillCatalogDirectory> skillCatalogDirectories;
    private final String mcpConfigurationJson;
    private final String configurationHash;
    private final CapabilityConfigurationEditor updatedBy;
    private final Instant updatedAt;
    private final long version;

    private CapabilitySourceConfiguration(
            List<CommandCatalogDirectory> commandCatalogDirectories,
            List<SkillCatalogDirectory> skillCatalogDirectories,
            String mcpConfigurationJson, CapabilityConfigurationEditor updatedBy,
            Instant updatedAt, long version) {
        this.commandCatalogDirectories = commandDirectories(commandCatalogDirectories);
        this.skillCatalogDirectories = skillDirectories(skillCatalogDirectories);
        requireSeparateCatalogDirectories(
                this.commandCatalogDirectories, this.skillCatalogDirectories);
        this.mcpConfigurationJson = DomainText.require(
                mcpConfigurationJson, "MCP configuration JSON",
                MAX_MCP_CONFIGURATION_LENGTH);
        if (updatedBy == null) {
            throw new IllegalArgumentException(
                    "capability configuration editor must not be null");
        }
        this.updatedBy = updatedBy;
        this.updatedAt = DomainText.requireTime(
                updatedAt, "capability configuration update time");
        if (version < 1L) {
            throw new IllegalArgumentException(
                    "capability source version must be positive");
        }
        this.version = version;
        this.configurationHash = calculateConfigurationHash();
    }

    public static CapabilitySourceConfiguration create(
            List<CommandCatalogDirectory> commandCatalogDirectories,
            List<SkillCatalogDirectory> skillCatalogDirectories,
            String mcpConfigurationJson, CapabilityConfigurationEditor updatedBy,
            Instant updatedAt) {
        return new CapabilitySourceConfiguration(
                commandCatalogDirectories, skillCatalogDirectories,
                mcpConfigurationJson, updatedBy, updatedAt, 1L);
    }

    public static void validateSources(
            List<CommandCatalogDirectory> commandCatalogDirectories,
            List<SkillCatalogDirectory> skillCatalogDirectories,
            String mcpConfigurationJson) {
        commandDirectories(commandCatalogDirectories);
        skillDirectories(skillCatalogDirectories);
        requireSeparateCatalogDirectories(
                commandCatalogDirectories, skillCatalogDirectories);
        DomainText.require(mcpConfigurationJson, "MCP configuration JSON",
                MAX_MCP_CONFIGURATION_LENGTH);
    }

    public static CapabilitySourceConfiguration restore(
            List<CommandCatalogDirectory> commandCatalogDirectories,
            List<SkillCatalogDirectory> skillCatalogDirectories,
            String mcpConfigurationJson, String expectedConfigurationHash,
            CapabilityConfigurationEditor updatedBy, Instant updatedAt,
            long version) {
        CapabilitySourceConfiguration restored = new CapabilitySourceConfiguration(
                commandCatalogDirectories, skillCatalogDirectories,
                mcpConfigurationJson, updatedBy, updatedAt, version);
        String expected = DomainText.requireSha256(
                expectedConfigurationHash, "capability source configuration hash");
        if (!restored.configurationHash.equals(expected)) {
            throw new IllegalStateException(
                    "capability source configuration hash does not match persisted state");
        }
        return restored;
    }

    public CapabilitySourceConfiguration update(
            long expectedVersion,
            List<CommandCatalogDirectory> commandCatalogDirectories,
            List<SkillCatalogDirectory> skillCatalogDirectories,
            String mcpConfigurationJson, CapabilityConfigurationEditor editor,
            Instant updateTime) {
        requireVersion(expectedVersion);
        return new CapabilitySourceConfiguration(
                commandCatalogDirectories, skillCatalogDirectories,
                mcpConfigurationJson, editor, updateTime, version + 1L);
    }

    public void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new CapabilitySourceVersionConflictException(expectedVersion, version);
        }
    }

    private static List<CommandCatalogDirectory> commandDirectories(
            List<CommandCatalogDirectory> source) {
        requireDirectoryList(source, "command catalog directories");
        List<CommandCatalogDirectory> copy = new ArrayList<CommandCatalogDirectory>(source);
        copy.sort(Comparator.comparing(CommandCatalogDirectory::getDirectoryIdentifier));
        Set<String> identifiers = new HashSet<String>();
        Set<String> paths = new HashSet<String>();
        for (CommandCatalogDirectory directory : copy) {
            if (!identifiers.add(directory.getDirectoryIdentifier())) {
                throw new IllegalArgumentException(
                        "command catalog directory identifiers must be unique");
            }
            if (!paths.add(directory.getAbsoluteDirectory())) {
                throw new IllegalArgumentException(
                        "command catalog directory paths must be unique");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<SkillCatalogDirectory> skillDirectories(
            List<SkillCatalogDirectory> source) {
        requireDirectoryList(source, "skill catalog directories");
        List<SkillCatalogDirectory> copy = new ArrayList<SkillCatalogDirectory>(source);
        copy.sort(Comparator.comparing(SkillCatalogDirectory::getDirectoryIdentifier));
        Set<String> identifiers = new HashSet<String>();
        Set<String> paths = new HashSet<String>();
        for (SkillCatalogDirectory directory : copy) {
            if (!identifiers.add(directory.getDirectoryIdentifier())) {
                throw new IllegalArgumentException(
                        "skill catalog directory identifiers must be unique");
            }
            if (!paths.add(directory.getAbsoluteDirectory())) {
                throw new IllegalArgumentException(
                        "skill catalog directory paths must be unique");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireDirectoryList(List<?> source, String name) {
        if (source == null || source.size() > MAX_DIRECTORIES_PER_CATALOG
                || source.contains(null)) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + MAX_DIRECTORIES_PER_CATALOG
                            + " non-null entries");
        }
    }

    private static void requireSeparateCatalogDirectories(
            List<CommandCatalogDirectory> commandDirectories,
            List<SkillCatalogDirectory> skillDirectories) {
        Set<String> commandPaths = new HashSet<String>();
        for (CommandCatalogDirectory directory : commandDirectories) {
            commandPaths.add(directory.getAbsoluteDirectory());
        }
        for (SkillCatalogDirectory directory : skillDirectories) {
            if (commandPaths.contains(directory.getAbsoluteDirectory())) {
                throw new IllegalArgumentException(
                        "command and skill catalogs must not share a directory");
            }
        }
    }

    private String calculateConfigurationHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        for (CommandCatalogDirectory directory : commandCatalogDirectories) {
            CanonicalHashing.appendFramed(canonical, "commandDirectoryIdentifier",
                    directory.getDirectoryIdentifier());
            CanonicalHashing.appendFramed(canonical, "commandAbsoluteDirectory",
                    directory.getAbsoluteDirectory());
            CanonicalHashing.appendFramed(canonical, "commandEnabled", directory.isEnabled());
        }
        for (SkillCatalogDirectory directory : skillCatalogDirectories) {
            CanonicalHashing.appendFramed(canonical, "skillDirectoryIdentifier",
                    directory.getDirectoryIdentifier());
            CanonicalHashing.appendFramed(canonical, "skillAbsoluteDirectory",
                    directory.getAbsoluteDirectory());
            CanonicalHashing.appendFramed(canonical, "skillTrustSource",
                    directory.getTrustSource().name());
            CanonicalHashing.appendFramed(canonical, "skillEnabled", directory.isEnabled());
        }
        CanonicalHashing.appendFramed(canonical, "mcpConfigurationJson", mcpConfigurationJson);
        return CanonicalHashing.sha256(canonical.toString());
    }
}
