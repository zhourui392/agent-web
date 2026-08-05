package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.RunMode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 可反复保存但尚未发布的 Stage 配置内容。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageDraftContent {

    private static final int MAX_COMMANDS = 100;
    private static final int MAX_SKILLS = 100;
    private static final int MAX_MCP_SERVERS = 50;

    private final int sequenceNumber;
    private final String displayName;
    private final String description;
    private final String stageRules;
    private final Set<RunMode> allowedRunModes;
    private final List<StageCommandSelection> commandSelections;
    private final List<StageSkillSelection> skillSelections;
    private final List<StageMcpServerSelection> mcpServerSelections;
    private final String draftHash;

    private WorkbenchStageDraftContent(
            int sequenceNumber, String displayName, String description,
            String stageRules, Set<RunMode> allowedRunModes,
            List<StageCommandSelection> commandSelections,
            List<StageSkillSelection> skillSelections,
            List<StageMcpServerSelection> mcpServerSelections) {
        if (sequenceNumber < 1) {
            throw invalid("Stage sequence number must be positive");
        }
        this.sequenceNumber = sequenceNumber;
        this.displayName = DomainText.require(displayName, "Stage display name", 120);
        this.description = normalizeDescription(description);
        this.stageRules = DomainText.require(stageRules, "Stage rules", 32 * 1024);
        this.allowedRunModes = runModes(allowedRunModes);
        this.commandSelections = selections(commandSelections,
                StageCommandSelection::getIdentifier, MAX_COMMANDS, "Stage Commands");
        this.skillSelections = selections(skillSelections,
                StageSkillSelection::getIdentifier, MAX_SKILLS, "Stage Skills");
        this.mcpServerSelections = selections(mcpServerSelections,
                StageMcpServerSelection::getIdentifier, MAX_MCP_SERVERS,
                "Stage MCP Servers");
        this.draftHash = calculateDraftHash();
    }

    private String normalizeDescription(String source) {
        if (source == null || source.trim().isEmpty()) {
            return "";
        }
        return DomainText.require(source, "Stage description", 2000);
    }

    public static WorkbenchStageDraftContent create(
            int sequenceNumber, String displayName, String description,
            String stageRules, Set<RunMode> allowedRunModes,
            List<StageCommandSelection> commandSelections,
            List<StageSkillSelection> skillSelections,
            List<StageMcpServerSelection> mcpServerSelections) {
        return new WorkbenchStageDraftContent(
                sequenceNumber, displayName, description, stageRules, allowedRunModes,
                commandSelections, skillSelections, mcpServerSelections);
    }

    public void requireResolvedCapabilities(ResolvedStageCapabilities resolved) {
        if (resolved == null
                || !commandSelectionsMatch(resolved.getCommands())
                || !skillSelectionsMatch(resolved.getSkills())
                || !mcpSelectionsMatch(resolved.getMcpServers())) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_CAPABILITY_UNAVAILABLE",
                    "resolved capabilities do not match Stage Draft selections");
        }
        boolean readOnly = allowedRunModes.stream().noneMatch(RunMode::modifiesWorkspace);
        if (readOnly && resolved.getMcpServers().stream()
                .anyMatch(reference -> reference.getMaximumAccess()
                        == CapabilityAccess.WRITE)) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_RUNTIME_INCOMPATIBLE",
                    "read-only Stage cannot select a write MCP Server");
        }
    }

    private boolean commandSelectionsMatch(List<StageCommandReference> references) {
        if (commandSelections.size() != references.size()) {
            return false;
        }
        for (int index = 0; index < commandSelections.size(); index++) {
            StageCommandSelection selected = commandSelections.get(index);
            StageCommandReference resolved = references.get(index);
            if (!selected.getIdentifier().equals(resolved.getIdentifier())
                    || !selected.getVersion().equals(resolved.getVersion())) {
                return false;
            }
        }
        return true;
    }

    private boolean skillSelectionsMatch(List<StageSkillReference> references) {
        if (skillSelections.size() != references.size()) {
            return false;
        }
        for (int index = 0; index < skillSelections.size(); index++) {
            StageSkillSelection selected = skillSelections.get(index);
            StageSkillReference resolved = references.get(index);
            if (!selected.getIdentifier().equals(resolved.getIdentifier())
                    || !selected.getVersion().equals(resolved.getVersion())
                    || selected.isRequired() != resolved.isRequired()) {
                return false;
            }
        }
        return true;
    }

    private boolean mcpSelectionsMatch(List<StageMcpServerReference> references) {
        if (mcpServerSelections.size() != references.size()) {
            return false;
        }
        for (int index = 0; index < mcpServerSelections.size(); index++) {
            StageMcpServerSelection selected = mcpServerSelections.get(index);
            StageMcpServerReference resolved = references.get(index);
            if (!selected.getIdentifier().equals(resolved.getIdentifier())
                    || !selected.getVersion().equals(resolved.getVersion())
                    || selected.isRequired() != resolved.isRequired()) {
                return false;
            }
        }
        return true;
    }

    private Set<RunMode> runModes(Set<RunMode> source) {
        if (source == null || source.isEmpty()) {
            throw invalid("Stage allowed Run Modes must not be empty");
        }
        for (RunMode mode : source) {
            if (mode == null) {
                throw invalid("Stage allowed Run Modes must not contain null");
            }
        }
        return Collections.unmodifiableSet(new TreeSet<RunMode>(source));
    }

    private <T> List<T> selections(
            List<T> source, Function<T, String> identifier,
            int maximumSize, String name) {
        if (source == null || source.size() > maximumSize) {
            throw invalid(name + " exceed the allowed selection limit");
        }
        List<T> copy = new ArrayList<T>(source);
        for (T selection : copy) {
            if (selection == null) {
                throw invalid(name + " must not contain null");
            }
        }
        copy.sort(Comparator.comparing(identifier));
        Set<String> identifiers = new HashSet<String>();
        for (T selection : copy) {
            if (!identifiers.add(identifier.apply(selection))) {
                throw invalid(name + " must use unique identifiers");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private String calculateDraftHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", "workbench-stage-draft@1");
        CanonicalHashing.appendFramed(canonical, "sequenceNumber", sequenceNumber);
        CanonicalHashing.appendFramed(canonical, "displayName", displayName);
        CanonicalHashing.appendFramed(canonical, "description", description);
        CanonicalHashing.appendFramed(canonical, "stageRules", stageRules);
        for (RunMode mode : allowedRunModes) {
            CanonicalHashing.appendFramed(canonical, "runMode", mode.name());
        }
        for (StageCommandSelection command : commandSelections) {
            appendSelection(canonical, "command", command.getIdentifier(),
                    command.getVersion(), false);
        }
        for (StageSkillSelection skill : skillSelections) {
            appendSelection(canonical, "skill", skill.getIdentifier(),
                    skill.getVersion(), skill.isRequired());
        }
        for (StageMcpServerSelection mcpServer : mcpServerSelections) {
            appendSelection(canonical, "mcp", mcpServer.getIdentifier(),
                    mcpServer.getVersion(), mcpServer.isRequired());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private void appendSelection(
            StringBuilder canonical, String kind, String identifier,
            String version, boolean required) {
        CanonicalHashing.appendFramed(canonical, kind + "Identifier", identifier);
        CanonicalHashing.appendFramed(canonical, kind + "Version", version);
        CanonicalHashing.appendFramed(canonical, kind + "Required", required);
    }

    private static StageCatalogException invalid(String message) {
        return new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID", message);
    }
}
