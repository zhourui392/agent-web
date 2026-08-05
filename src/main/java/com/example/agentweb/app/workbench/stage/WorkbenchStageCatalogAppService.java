package com.example.agentweb.app.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.capability.CommandCatalog;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.StageCatalogException;
import com.example.agentweb.domain.workbench.stage.StageCommandReference;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageMcpServerReference;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.StageSkillReference;
import com.example.agentweb.domain.workbench.stage.StageSkillSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalogRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinition;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage Draft、发布、停用和精确 Capability 归档用例。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
@Transactional(readOnly = true)
public class WorkbenchStageCatalogAppService {

    private final WorkbenchStageCatalogRepository repository;
    private final CommandCatalog commandCatalog;
    private final SkillCatalog skillCatalog;
    private final McpServerCatalog mcpServerCatalog;
    private final CapabilityArtifactRegistry artifactRegistry;
    private final Clock clock;

    public WorkbenchStageCatalogAppService(
            WorkbenchStageCatalogRepository repository,
            CommandCatalog commandCatalog, SkillCatalog skillCatalog,
            McpServerCatalog mcpServerCatalog,
            CapabilityArtifactRegistry artifactRegistry, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
        this.mcpServerCatalog = Objects.requireNonNull(
                mcpServerCatalog, "mcpServerCatalog");
        this.artifactRegistry = Objects.requireNonNull(
                artifactRegistry, "artifactRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkbenchStageCatalog find() {
        return repository.find();
    }

    @Transactional
    public WorkbenchStageDefinition createDraft(
            String definitionIdentifier, WorkbenchStageDraftContent content,
            long expectedCatalogVersion, StageCatalogEditor editor) {
        WorkbenchStageCatalog catalog = repository.find();
        catalog.requireCatalogVersion(expectedCatalogVersion);
        catalog.createDraft(definitionIdentifier, content, editor, clock.instant());
        repository.save(catalog, expectedCatalogVersion,
                definitionIdentifier, 0L);
        return catalog.requireDefinition(definitionIdentifier);
    }

    @Transactional
    public WorkbenchStageDefinition saveDraft(
            String definitionIdentifier, WorkbenchStageDraftContent content,
            long expectedDefinitionVersion, StageCatalogEditor editor) {
        WorkbenchStageCatalog catalog = repository.find();
        long catalogVersion = catalog.getCatalogVersion();
        catalog.saveDraft(definitionIdentifier, expectedDefinitionVersion,
                content, editor, clock.instant());
        repository.save(catalog, catalogVersion,
                definitionIdentifier, expectedDefinitionVersion);
        return catalog.requireDefinition(definitionIdentifier);
    }

    @Transactional
    public WorkbenchStageDefinitionRevision publishDraft(
            String definitionIdentifier, long expectedCatalogVersion,
            long expectedDefinitionVersion, StageCatalogEditor editor) {
        WorkbenchStageCatalog catalog = repository.find();
        catalog.requireCatalogVersion(expectedCatalogVersion);
        WorkbenchStageDefinition definition =
                catalog.requireDefinition(definitionIdentifier);
        definition.requireVersion(expectedDefinitionVersion);
        if (!definition.hasDraft()) {
            throw unavailable("Stage Definition has no Draft to publish");
        }
        ResolvedArtifacts artifacts = resolveArtifacts(
                definition.getDraft().getContent());
        archive(artifacts);
        WorkbenchStageDefinitionRevision published = catalog.publishDraft(
                definitionIdentifier, expectedCatalogVersion,
                expectedDefinitionVersion, artifacts.references,
                editor, clock.instant());
        repository.save(catalog, expectedCatalogVersion,
                definitionIdentifier, expectedDefinitionVersion);
        return published;
    }

    @Transactional
    public WorkbenchStageDefinition disable(
            String definitionIdentifier, long expectedCatalogVersion,
            long expectedDefinitionVersion, StageCatalogEditor editor) {
        WorkbenchStageCatalog catalog = repository.find();
        catalog.disable(definitionIdentifier, expectedCatalogVersion,
                expectedDefinitionVersion, editor, clock.instant());
        repository.save(catalog, expectedCatalogVersion,
                definitionIdentifier, expectedDefinitionVersion);
        return catalog.requireDefinition(definitionIdentifier);
    }

    private ResolvedArtifacts resolveArtifacts(WorkbenchStageDraftContent draft) {
        Map<String, CommandDefinition> commands = indexCommands(commandCatalog.discover());
        Map<String, SkillPackage> skills = indexSkills(skillCatalog.discover());
        Map<String, McpServerDefinition> mcpServers = indexMcpServers(
                mcpServerCatalog.discover());
        List<CommandDefinition> selectedCommands = new ArrayList<CommandDefinition>();
        List<StageCommandReference> commandReferences =
                new ArrayList<StageCommandReference>();
        for (StageCommandSelection selection : draft.getCommandSelections()) {
            CommandDefinition command = requireArtifact(
                    commands, selection.getIdentifier(), selection.getVersion(), "Command");
            selectedCommands.add(command);
            commandReferences.add(new StageCommandReference(
                    command.getIdentifier(), command.getVersion(), command.getContentHash()));
        }
        List<SkillPackage> selectedSkills = new ArrayList<SkillPackage>();
        List<StageSkillReference> skillReferences = new ArrayList<StageSkillReference>();
        for (StageSkillSelection selection : draft.getSkillSelections()) {
            SkillPackage skill = requireArtifact(
                    skills, selection.getIdentifier(), selection.getVersion(), "Skill");
            selectedSkills.add(skill);
            skillReferences.add(new StageSkillReference(
                    skill.getManifest().getId(), skill.getManifest().getVersion(),
                    skill.getPackageHash(), selection.isRequired()));
        }
        List<McpServerDefinition> selectedMcpServers =
                new ArrayList<McpServerDefinition>();
        List<StageMcpServerReference> mcpReferences =
                new ArrayList<StageMcpServerReference>();
        for (StageMcpServerSelection selection : draft.getMcpServerSelections()) {
            McpServerDefinition mcpServer = requireArtifact(
                    mcpServers, selection.getIdentifier(), selection.getVersion(),
                    "MCP Server");
            selectedMcpServers.add(mcpServer);
            mcpReferences.add(new StageMcpServerReference(
                    mcpServer.getId(), mcpServer.getVersion(),
                    mcpServer.getConfigurationHash(), selection.isRequired(),
                    mcpServer.getMaximumAccess(), mcpServer.getTransport().name()));
        }
        ResolvedStageCapabilities references = new ResolvedStageCapabilities(
                commandReferences, skillReferences, mcpReferences);
        draft.requireResolvedCapabilities(references);
        return new ResolvedArtifacts(
                selectedCommands, selectedSkills, selectedMcpServers, references);
    }

    private void archive(ResolvedArtifacts artifacts) {
        for (CommandDefinition command : artifacts.commands) {
            artifactRegistry.archiveCommand(command);
        }
        for (SkillPackage skill : artifacts.skills) {
            artifactRegistry.archiveSkill(skill);
        }
        for (McpServerDefinition mcpServer : artifacts.mcpServers) {
            artifactRegistry.archiveMcpServer(mcpServer);
        }
    }

    private Map<String, CommandDefinition> indexCommands(List<CommandDefinition> discovered) {
        Map<String, CommandDefinition> indexed = new HashMap<String, CommandDefinition>();
        for (CommandDefinition command : requireCatalog(discovered, "Command")) {
            putUnique(indexed, command.getIdentifier(), command.getVersion(), command, "Command");
        }
        return indexed;
    }

    private Map<String, SkillPackage> indexSkills(List<SkillPackage> discovered) {
        Map<String, SkillPackage> indexed = new HashMap<String, SkillPackage>();
        for (SkillPackage skill : requireCatalog(discovered, "Skill")) {
            putUnique(indexed, skill.getManifest().getId(),
                    skill.getManifest().getVersion(), skill, "Skill");
        }
        return indexed;
    }

    private Map<String, McpServerDefinition> indexMcpServers(
            List<McpServerDefinition> discovered) {
        Map<String, McpServerDefinition> indexed =
                new HashMap<String, McpServerDefinition>();
        for (McpServerDefinition mcpServer : requireCatalog(discovered, "MCP Server")) {
            putUnique(indexed, mcpServer.getId(), mcpServer.getVersion(),
                    mcpServer, "MCP Server");
        }
        return indexed;
    }

    private <T> List<T> requireCatalog(List<T> values, String kind) {
        if (values == null) {
            throw unavailable(kind + " Catalog returned invalid content");
        }
        for (T value : values) {
            if (value == null) {
                throw unavailable(kind + " Catalog returned invalid content");
            }
        }
        return values;
    }

    private <T> void putUnique(
            Map<String, T> indexed, String identifier, String version,
            T value, String kind) {
        if (indexed.put(key(identifier, version), value) != null) {
            throw unavailable(kind + " Catalog contains a duplicate exact version");
        }
    }

    private <T> T requireArtifact(
            Map<String, T> indexed, String identifier, String version, String kind) {
        T value = indexed.get(key(identifier, version));
        if (value == null) {
            throw unavailable(kind + " exact version is unavailable");
        }
        return value;
    }

    private String key(String identifier, String version) {
        return identifier + '\0' + version;
    }

    private StageCatalogException unavailable(String message) {
        return new StageCatalogException(
                "WORKBENCH_STAGE_CAPABILITY_UNAVAILABLE", message);
    }

    private static final class ResolvedArtifacts {
        private final List<CommandDefinition> commands;
        private final List<SkillPackage> skills;
        private final List<McpServerDefinition> mcpServers;
        private final ResolvedStageCapabilities references;

        private ResolvedArtifacts(
                List<CommandDefinition> commands, List<SkillPackage> skills,
                List<McpServerDefinition> mcpServers,
                ResolvedStageCapabilities references) {
            this.commands = commands;
            this.skills = skills;
            this.mcpServers = mcpServers;
            this.references = references;
        }
    }
}
