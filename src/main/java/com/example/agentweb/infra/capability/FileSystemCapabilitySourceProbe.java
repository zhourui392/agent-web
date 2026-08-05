package com.example.agentweb.infra.capability;

import com.example.agentweb.app.capability.CapabilityDiscoveryItem;
import com.example.agentweb.app.capability.CapabilitySourceCandidate;
import com.example.agentweb.app.capability.CapabilitySourceProbeResult;
import com.example.agentweb.app.capability.port.CapabilitySourceProbe;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.SkillCatalogDirectory;
import com.example.agentweb.domain.capability.SkillPackage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Capability Source 保存前的真实目录、Catalog 与 MCP JSON 探测器。
 *
 * @author alex
 * @since 2026-08-05
 */
@Component
public class FileSystemCapabilitySourceProbe implements CapabilitySourceProbe {

    private final JsonMcpServerCatalog mcpServerCatalog;
    private final Clock clock;

    public FileSystemCapabilitySourceProbe(ObjectMapper objectMapper, Clock clock) {
        this.mcpServerCatalog = new JsonMcpServerCatalog(objectMapper);
        if (clock == null) {
            throw new IllegalArgumentException("capability source probe clock is required");
        }
        this.clock = clock;
    }

    @Override
    public CapabilitySourceProbeResult probe(CapabilitySourceCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("capability source candidate is required");
        }
        List<CommandCatalogDirectory> commandDirectories =
                commandDirectories(candidate.getCommandCatalogDirectories());
        List<SkillCatalogDirectory> skillDirectories =
                skillDirectories(candidate.getSkillCatalogDirectories());
        ParsedMcpServerCatalog parsedMcp = mcpServerCatalog.parse(
                candidate.getMcpConfigurationJson());
        CapabilitySourceConfiguration.validateSources(
                commandDirectories, skillDirectories, parsedMcp.getCanonicalJson());
        return new CapabilitySourceProbeResult(
                commandDirectories, skillDirectories, parsedMcp.getCanonicalJson(),
                commandItems(commandDirectories), skillItems(skillDirectories),
                mcpItems(parsedMcp.getDefinitions()), Collections.emptyList());
    }

    private List<CommandCatalogDirectory> commandDirectories(
            List<CommandCatalogDirectory> directories) {
        List<CommandCatalogDirectory> canonical = new ArrayList<CommandCatalogDirectory>();
        for (CommandCatalogDirectory directory : directories) {
            canonical.add(CommandCatalogDirectory.create(
                    directory.getDirectoryIdentifier(),
                    realDirectory(directory.getAbsoluteDirectory()).toString(),
                    directory.isEnabled()));
        }
        return Collections.unmodifiableList(canonical);
    }

    private List<SkillCatalogDirectory> skillDirectories(
            List<SkillCatalogDirectory> directories) {
        List<SkillCatalogDirectory> canonical = new ArrayList<SkillCatalogDirectory>();
        for (SkillCatalogDirectory directory : directories) {
            canonical.add(SkillCatalogDirectory.create(
                    directory.getDirectoryIdentifier(),
                    realDirectory(directory.getAbsoluteDirectory()).toString(),
                    directory.getTrustSource(), directory.isEnabled()));
        }
        return Collections.unmodifiableList(canonical);
    }

    private Path realDirectory(String value) {
        Path configured = Path.of(value).normalize();
        Path current = configured.getRoot();
        for (Path segment : configured) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new CapabilityCatalogException("CATALOG_PATH_UNSAFE",
                        "capability source path must not contain symbolic links");
            }
        }
        try {
            Path real = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new CapabilityCatalogException("CATALOG_ROOT_INVALID",
                        "capability source must be a directory");
            }
            return real;
        } catch (IOException failure) {
            throw new CapabilityCatalogException("CATALOG_ROOT_INVALID",
                    "capability source directory is not accessible", failure);
        }
    }

    private List<CapabilityDiscoveryItem> commandItems(
            List<CommandCatalogDirectory> directories) {
        List<CommandDefinition> commands = new FileSystemCommandCatalog(
                directories, clock).discover();
        List<CapabilityDiscoveryItem> items = new ArrayList<CapabilityDiscoveryItem>();
        for (CommandDefinition command : commands) {
            items.add(new CapabilityDiscoveryItem(
                    command.getIdentifier(), command.getVersion(),
                    command.getContentHash(), command.getDisplayName()));
        }
        return Collections.unmodifiableList(items);
    }

    private List<CapabilityDiscoveryItem> skillItems(
            List<SkillCatalogDirectory> directories) {
        Map<String, SkillPackage> packages = new HashMap<String, SkillPackage>();
        for (SkillCatalogDirectory directory : directories) {
            if (!directory.isEnabled()) {
                continue;
            }
            List<SkillPackage> discovered = new FileSystemSkillCatalog(
                    Path.of(directory.getAbsoluteDirectory()),
                    directory.getTrustSource()).discover();
            for (SkillPackage skill : discovered) {
                String key = skill.getManifest().getId() + "\u0000"
                        + skill.getManifest().getVersion();
                SkillPackage existing = packages.putIfAbsent(key, skill);
                if (existing != null
                        && !existing.getPackageHash().equals(skill.getPackageHash())) {
                    throw new CapabilityCatalogException(
                            "CATALOG_SKILL_CONTENT_CONFLICT",
                            "same Skill identifier and version have different content");
                }
            }
        }
        List<SkillPackage> ordered = new ArrayList<SkillPackage>(packages.values());
        ordered.sort(Comparator.comparing((SkillPackage value) -> value.getManifest().getId())
                .thenComparing(value -> value.getManifest().getVersion()));
        List<CapabilityDiscoveryItem> items = new ArrayList<CapabilityDiscoveryItem>();
        for (SkillPackage skill : ordered) {
            items.add(new CapabilityDiscoveryItem(
                    skill.getManifest().getId(), skill.getManifest().getVersion(),
                    skill.getPackageHash(), skill.getManifest().getDescription()));
        }
        return Collections.unmodifiableList(items);
    }

    private List<CapabilityDiscoveryItem> mcpItems(
            List<McpServerDefinition> definitions) {
        List<CapabilityDiscoveryItem> items = new ArrayList<CapabilityDiscoveryItem>();
        for (McpServerDefinition definition : definitions) {
            items.add(new CapabilityDiscoveryItem(
                    definition.getId(), definition.getVersion(),
                    definition.getConfigurationHash(), definition.getDisplayName()));
        }
        return Collections.unmodifiableList(items);
    }
}
