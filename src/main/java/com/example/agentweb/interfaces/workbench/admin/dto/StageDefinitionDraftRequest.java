package com.example.agentweb.interfaces.workbench.admin.dto;

import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.StageSkillSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stage Definition Draft 管理请求。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@Setter
public final class StageDefinitionDraftRequest {

    private String definitionIdentifier;
    private Integer sequenceNumber;
    private String displayName;
    private String description;
    private String stageRules;
    private Set<RunMode> allowedRunModes;
    private List<CommandSelectionRequest> commandReferences;
    private List<SkillSelectionRequest> skillReferences;
    private List<McpServerSelectionRequest> mcpServerReferences;

    public WorkbenchStageDraftContent toDraftContent() {
        if (sequenceNumber == null || commandReferences == null
                || skillReferences == null || mcpServerReferences == null) {
            throw new IllegalArgumentException(
                    "complete Stage Definition Draft request is required");
        }
        return WorkbenchStageDraftContent.create(
                sequenceNumber.intValue(), displayName, description, stageRules,
                allowedRunModes, commandSelections(), skillSelections(),
                mcpServerSelections());
    }

    private List<StageCommandSelection> commandSelections() {
        List<StageCommandSelection> selections =
                new ArrayList<StageCommandSelection>(commandReferences.size());
        for (CommandSelectionRequest request : commandReferences) {
            if (request == null) {
                throw new IllegalArgumentException(
                        "Stage Command reference must not be null");
            }
            selections.add(new StageCommandSelection(
                    request.getIdentifier(), request.getVersion()));
        }
        return selections;
    }

    private List<StageSkillSelection> skillSelections() {
        List<StageSkillSelection> selections =
                new ArrayList<StageSkillSelection>(skillReferences.size());
        for (SkillSelectionRequest request : skillReferences) {
            if (request == null) {
                throw new IllegalArgumentException(
                        "Stage Skill reference must not be null");
            }
            selections.add(new StageSkillSelection(
                    request.getIdentifier(), request.getVersion(), request.isRequired()));
        }
        return selections;
    }

    private List<StageMcpServerSelection> mcpServerSelections() {
        List<StageMcpServerSelection> selections =
                new ArrayList<StageMcpServerSelection>(mcpServerReferences.size());
        for (McpServerSelectionRequest request : mcpServerReferences) {
            if (request == null) {
                throw new IllegalArgumentException(
                        "Stage MCP Server reference must not be null");
            }
            selections.add(new StageMcpServerSelection(
                    request.getIdentifier(), request.getVersion(), request.isRequired()));
        }
        return selections;
    }

    /**
     * Command 精确版本选择请求。
     *
     * @author alex
     * @since 2026-08-05
     */
    @Getter
    @Setter
    public static final class CommandSelectionRequest {
        private String identifier;
        private String version;
    }

    /**
     * Skill 精确版本选择请求。
     *
     * @author alex
     * @since 2026-08-05
     */
    @Getter
    @Setter
    public static final class SkillSelectionRequest {
        private String identifier;
        private String version;
        private boolean required;
    }

    /**
     * MCP Server 精确版本选择请求。
     *
     * @author alex
     * @since 2026-08-05
     */
    @Getter
    @Setter
    public static final class McpServerSelectionRequest {
        private String identifier;
        private String version;
        private boolean required;
    }
}
