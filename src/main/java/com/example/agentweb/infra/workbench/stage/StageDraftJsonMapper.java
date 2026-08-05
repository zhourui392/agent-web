package com.example.agentweb.infra.workbench.stage;

import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.StageSkillSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage Draft 与 Run Mode 的 SQLite JSON 映射。
 *
 * @author alex
 * @since 2026-08-05
 */
final class StageDraftJsonMapper {

    private final ObjectMapper objectMapper;

    StageDraftJsonMapper(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Stage Draft ObjectMapper is required");
        }
        this.objectMapper = objectMapper;
    }

    String draft(WorkbenchStageDraftContent content) {
        DraftDocument document = new DraftDocument();
        document.sequenceNumber = content.getSequenceNumber();
        document.displayName = content.getDisplayName();
        document.description = content.getDescription();
        document.stageRules = content.getStageRules();
        document.allowedRunModes = runModeNames(content.getAllowedRunModes());
        document.commands = commandDocuments(content.getCommandSelections());
        document.skills = skillDocuments(content.getSkillSelections());
        document.mcpServers = mcpDocuments(content.getMcpServerSelections());
        return write(document);
    }

    WorkbenchStageDraftContent draft(String json) {
        try {
            DraftDocument document = objectMapper.readValue(json, DraftDocument.class);
            return WorkbenchStageDraftContent.create(
                    document.sequenceNumber, document.displayName,
                    document.description, document.stageRules,
                    runModes(document.allowedRunModes),
                    commands(document.commands), skills(document.skills),
                    mcpServers(document.mcpServers));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalStateException("cannot restore Stage Draft JSON", failure);
        }
    }

    String runModes(Set<RunMode> runModes) {
        return write(runModeNames(runModes));
    }

    Set<RunMode> runModes(String json) {
        try {
            List<String> names = objectMapper.readValue(
                    json, new TypeReference<List<String>>() { });
            return runModes(names);
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalStateException("cannot restore Stage Run Modes", failure);
        }
    }

    private List<String> runModeNames(Set<RunMode> runModes) {
        List<String> names = new ArrayList<String>();
        for (RunMode runMode : runModes) {
            names.add(runMode.name());
        }
        return names;
    }

    private Set<RunMode> runModes(List<String> names) {
        if (names == null || names.isEmpty() || names.contains(null)) {
            throw new IllegalStateException("persisted Stage Run Modes are invalid");
        }
        Set<RunMode> runModes = new LinkedHashSet<RunMode>();
        for (String name : names) {
            runModes.add(RunMode.valueOf(name));
        }
        return runModes;
    }

    private List<CommandDocument> commandDocuments(
            List<StageCommandSelection> selections) {
        List<CommandDocument> documents = new ArrayList<CommandDocument>();
        for (StageCommandSelection selection : selections) {
            CommandDocument document = new CommandDocument();
            document.identifier = selection.getIdentifier();
            document.version = selection.getVersion();
            documents.add(document);
        }
        return documents;
    }

    private List<StageCommandSelection> commands(List<CommandDocument> documents) {
        if (documents == null || documents.contains(null)) {
            throw new IllegalStateException("persisted Stage Commands are invalid");
        }
        List<StageCommandSelection> selections =
                new ArrayList<StageCommandSelection>();
        for (CommandDocument document : documents) {
            selections.add(new StageCommandSelection(
                    document.identifier, document.version));
        }
        return selections;
    }

    private List<SkillDocument> skillDocuments(List<StageSkillSelection> selections) {
        List<SkillDocument> documents = new ArrayList<SkillDocument>();
        for (StageSkillSelection selection : selections) {
            SkillDocument document = new SkillDocument();
            document.identifier = selection.getIdentifier();
            document.version = selection.getVersion();
            document.required = selection.isRequired();
            documents.add(document);
        }
        return documents;
    }

    private List<StageSkillSelection> skills(List<SkillDocument> documents) {
        if (documents == null || documents.contains(null)) {
            throw new IllegalStateException("persisted Stage Skills are invalid");
        }
        List<StageSkillSelection> selections = new ArrayList<StageSkillSelection>();
        for (SkillDocument document : documents) {
            selections.add(new StageSkillSelection(
                    document.identifier, document.version, document.required));
        }
        return selections;
    }

    private List<McpDocument> mcpDocuments(
            List<StageMcpServerSelection> selections) {
        List<McpDocument> documents = new ArrayList<McpDocument>();
        for (StageMcpServerSelection selection : selections) {
            McpDocument document = new McpDocument();
            document.identifier = selection.getIdentifier();
            document.version = selection.getVersion();
            document.required = selection.isRequired();
            documents.add(document);
        }
        return documents;
    }

    private List<StageMcpServerSelection> mcpServers(List<McpDocument> documents) {
        if (documents == null || documents.contains(null)) {
            throw new IllegalStateException("persisted Stage MCP Servers are invalid");
        }
        List<StageMcpServerSelection> selections =
                new ArrayList<StageMcpServerSelection>();
        for (McpDocument document : documents) {
            selections.add(new StageMcpServerSelection(
                    document.identifier, document.version, document.required));
        }
        return selections;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize Stage JSON", failure);
        }
    }

    public static final class DraftDocument {
        public int sequenceNumber;
        public String displayName;
        public String description;
        public String stageRules;
        public List<String> allowedRunModes;
        public List<CommandDocument> commands;
        public List<SkillDocument> skills;
        public List<McpDocument> mcpServers;
    }

    public static final class CommandDocument {
        public String identifier;
        public String version;
    }

    public static final class SkillDocument {
        public String identifier;
        public String version;
        public boolean required;
    }

    public static final class McpDocument {
        public String identifier;
        public String version;
        public boolean required;
    }
}
