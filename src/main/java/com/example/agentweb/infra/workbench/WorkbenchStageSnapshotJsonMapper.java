package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.StageCommandReference;
import com.example.agentweb.domain.workbench.stage.StageMcpServerReference;
import com.example.agentweb.domain.workbench.stage.StageSkillReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Workbench Stage Snapshot 与持久化 JSON 的严格映射器。
 *
 * @author alex
 * @since 2026-08-05
 */
public final class WorkbenchStageSnapshotJsonMapper {

    private static final String SCHEMA = "workbench-stage-snapshot@1";

    private final ObjectMapper objectMapper;

    public WorkbenchStageSnapshotJsonMapper(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Snapshot ObjectMapper is required");
        }
        this.objectMapper = objectMapper;
    }

    public String write(WorkbenchStageSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Workbench Stage Snapshot is required");
        }
        try {
            return objectMapper.writeValueAsString(SnapshotDocument.from(snapshot));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Workbench Stage Snapshot could not be serialized", exception);
        }
    }

    public WorkbenchStageSnapshot read(String json, String expectedSnapshotHash) {
        try {
            SnapshotDocument document = objectMapper.readValue(
                    json, SnapshotDocument.class);
            if (!SCHEMA.equals(document.schema())) {
                throw new IllegalStateException(
                        "persisted Workbench Stage Snapshot schema is unsupported");
            }
            return WorkbenchStageSnapshot.restore(
                    document.definitionIdentifier(), document.definitionRevision(),
                    document.definitionHash(), document.sequenceNumber(),
                    document.displayName(), document.description(),
                    document.stageRules(), runModes(document.allowedRunModes()),
                    commands(document.commandReferences()),
                    skills(document.skillReferences()),
                    mcpServers(document.mcpServerReferences()), expectedSnapshotHash);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "persisted Workbench Stage Snapshot JSON is invalid", exception);
        }
    }

    private Set<RunMode> runModes(List<String> names) {
        if (names == null) {
            throw new IllegalArgumentException(
                    "persisted Workbench Stage Run Modes are required");
        }
        Set<RunMode> modes = new LinkedHashSet<RunMode>();
        for (String name : names) {
            modes.add(RunMode.valueOf(name));
        }
        return modes;
    }

    private List<StageCommandReference> commands(
            List<CommandReferenceDocument> documents) {
        if (documents == null) {
            throw new IllegalArgumentException(
                    "persisted Workbench Stage Commands are required");
        }
        List<StageCommandReference> references =
                new ArrayList<StageCommandReference>(documents.size());
        for (CommandReferenceDocument document : documents) {
            references.add(new StageCommandReference(
                    document.identifier(), document.version(), document.contentHash()));
        }
        return references;
    }

    private List<StageSkillReference> skills(
            List<SkillReferenceDocument> documents) {
        if (documents == null) {
            throw new IllegalArgumentException(
                    "persisted Workbench Stage Skills are required");
        }
        List<StageSkillReference> references =
                new ArrayList<StageSkillReference>(documents.size());
        for (SkillReferenceDocument document : documents) {
            references.add(new StageSkillReference(
                    document.identifier(), document.version(), document.packageHash(),
                    document.required()));
        }
        return references;
    }

    private List<StageMcpServerReference> mcpServers(
            List<McpServerReferenceDocument> documents) {
        if (documents == null) {
            throw new IllegalArgumentException(
                    "persisted Workbench Stage MCP Servers are required");
        }
        List<StageMcpServerReference> references =
                new ArrayList<StageMcpServerReference>(documents.size());
        for (McpServerReferenceDocument document : documents) {
            references.add(new StageMcpServerReference(
                    document.identifier(), document.version(),
                    document.definitionHash(), document.required(),
                    CapabilityAccess.valueOf(document.maximumAccess()),
                    document.transport()));
        }
        return references;
    }

    private record SnapshotDocument(
            String schema,
            String definitionIdentifier,
            long definitionRevision,
            String definitionHash,
            int sequenceNumber,
            String displayName,
            String description,
            String stageRules,
            List<String> allowedRunModes,
            List<CommandReferenceDocument> commandReferences,
            List<SkillReferenceDocument> skillReferences,
            List<McpServerReferenceDocument> mcpServerReferences) {

        private static SnapshotDocument from(WorkbenchStageSnapshot snapshot) {
            return new SnapshotDocument(
                    SCHEMA, snapshot.getDefinitionIdentifier(),
                    snapshot.getDefinitionRevision(), snapshot.getDefinitionHash(),
                    snapshot.getSequenceNumber(), snapshot.getDisplayName(),
                    snapshot.getDescription(), snapshot.getStageRules(),
                    snapshot.getAllowedRunModes().stream().map(Enum::name).toList(),
                    snapshot.getCommandReferences().stream()
                            .map(CommandReferenceDocument::from).toList(),
                    snapshot.getSkillReferences().stream()
                            .map(SkillReferenceDocument::from).toList(),
                    snapshot.getMcpServerReferences().stream()
                            .map(McpServerReferenceDocument::from).toList());
        }
    }

    private record CommandReferenceDocument(
            String identifier, String version, String contentHash) {

        private static CommandReferenceDocument from(StageCommandReference reference) {
            return new CommandReferenceDocument(
                    reference.getIdentifier(), reference.getVersion(),
                    reference.getContentHash());
        }
    }

    private record SkillReferenceDocument(
            String identifier, String version, String packageHash, boolean required) {

        private static SkillReferenceDocument from(StageSkillReference reference) {
            return new SkillReferenceDocument(
                    reference.getIdentifier(), reference.getVersion(),
                    reference.getPackageHash(), reference.isRequired());
        }
    }

    private record McpServerReferenceDocument(
            String identifier, String version, String definitionHash,
            boolean required, String maximumAccess, String transport) {

        private static McpServerReferenceDocument from(
                StageMcpServerReference reference) {
            return new McpServerReferenceDocument(
                    reference.getIdentifier(), reference.getVersion(),
                    reference.getDefinitionHash(), reference.isRequired(),
                    reference.getMaximumAccess().name(), reference.getTransport());
        }
    }
}
