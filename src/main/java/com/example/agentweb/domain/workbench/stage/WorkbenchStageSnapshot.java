package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 创建 Workbench 时从 Published Revision 冻结的不可变 Stage 配置。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageSnapshot {

    private final String definitionIdentifier;
    private final long definitionRevision;
    private final String definitionHash;
    private final int sequenceNumber;
    private final String displayName;
    private final String description;
    private final String stageRules;
    private final Set<RunMode> allowedRunModes;
    private final List<StageCommandReference> commandReferences;
    private final List<StageSkillReference> skillReferences;
    private final List<StageMcpServerReference> mcpServerReferences;
    private final String snapshotHash;

    private WorkbenchStageSnapshot(
            String definitionIdentifier, long definitionRevision,
            String definitionHash, int sequenceNumber,
            String displayName, String description, String stageRules,
            Set<RunMode> allowedRunModes,
            List<StageCommandReference> commandReferences,
            List<StageSkillReference> skillReferences,
            List<StageMcpServerReference> mcpServerReferences) {
        this.definitionIdentifier = DomainText.require(
                definitionIdentifier, "Stage Snapshot definition identifier", 128);
        if (definitionRevision < 1L || sequenceNumber < 1) {
            throw new IllegalArgumentException(
                    "Stage Snapshot revision and sequence must be positive");
        }
        this.definitionRevision = definitionRevision;
        this.definitionHash = DomainText.requireSha256(
                definitionHash, "Stage Snapshot definition hash");
        this.sequenceNumber = sequenceNumber;
        this.displayName = DomainText.require(
                displayName, "Stage Snapshot display name", 120);
        this.description = DomainText.require(
                description, "Stage Snapshot description", 2000);
        this.stageRules = DomainText.require(
                stageRules, "Stage Snapshot rules", 32 * 1024);
        this.allowedRunModes = immutableRunModes(allowedRunModes);
        this.commandReferences = immutableReferences(
                commandReferences, "Stage Snapshot Commands");
        this.skillReferences = immutableReferences(
                skillReferences, "Stage Snapshot Skills");
        this.mcpServerReferences = immutableReferences(
                mcpServerReferences, "Stage Snapshot MCP Servers");
        this.snapshotHash = calculateSnapshotHash();
    }

    public static WorkbenchStageSnapshot fromPublishedRevision(
            WorkbenchStageDefinitionRevision revision) {
        if (revision == null) {
            throw new IllegalArgumentException(
                    "Published Stage Revision is required");
        }
        return new WorkbenchStageSnapshot(
                revision.getDefinitionIdentifier(), revision.getRevisionNumber(),
                revision.getDefinitionHash(), revision.getSequenceNumber(),
                revision.getDisplayName(), revision.getDescription(),
                revision.getStageRules(), revision.getAllowedRunModes(),
                revision.getCommandReferences(), revision.getSkillReferences(),
                revision.getMcpServerReferences());
    }

    public static WorkbenchStageSnapshot restore(
            String definitionIdentifier, long definitionRevision,
            String definitionHash, int sequenceNumber,
            String displayName, String description, String stageRules,
            Set<RunMode> allowedRunModes,
            List<StageCommandReference> commandReferences,
            List<StageSkillReference> skillReferences,
            List<StageMcpServerReference> mcpServerReferences,
            String expectedSnapshotHash) {
        WorkbenchStageSnapshot restored = new WorkbenchStageSnapshot(
                definitionIdentifier, definitionRevision, definitionHash,
                sequenceNumber, displayName, description, stageRules,
                allowedRunModes, commandReferences, skillReferences,
                mcpServerReferences);
        if (!restored.snapshotHash.equals(expectedSnapshotHash)) {
            throw new IllegalStateException(
                    "persisted Workbench Stage Snapshot Hash does not match its content");
        }
        return restored;
    }

    public void requireRunModeAllowed(RunMode runMode) {
        if (runMode == null) {
            throw new IllegalArgumentException("Stage Run Mode is required");
        }
        if (!allowedRunModes.contains(runMode)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "Stage Snapshot does not allow Run Mode: " + runMode);
        }
    }

    private Set<RunMode> immutableRunModes(Set<RunMode> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(
                    "Stage Snapshot allowed Run Modes must not be empty");
        }
        TreeSet<RunMode> copy = new TreeSet<RunMode>();
        for (RunMode mode : source) {
            if (mode == null) {
                throw new IllegalArgumentException(
                        "Stage Snapshot allowed Run Modes must not contain null");
            }
            copy.add(mode);
        }
        return Collections.unmodifiableSet(copy);
    }

    private <T> List<T> immutableReferences(List<T> source, String name) {
        if (source == null) {
            throw new IllegalArgumentException(name + " are required");
        }
        List<T> copy = new ArrayList<T>(source.size());
        for (T value : source) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private String calculateSnapshotHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(
                canonical, "schema", "workbench-stage-snapshot@1");
        CanonicalHashing.appendFramed(
                canonical, "definitionIdentifier", definitionIdentifier);
        CanonicalHashing.appendFramed(
                canonical, "definitionRevision", definitionRevision);
        CanonicalHashing.appendFramed(canonical, "definitionHash", definitionHash);
        CanonicalHashing.appendFramed(canonical, "sequenceNumber", sequenceNumber);
        CanonicalHashing.appendFramed(canonical, "displayName", displayName);
        CanonicalHashing.appendFramed(canonical, "description", description);
        CanonicalHashing.appendFramed(canonical, "stageRules", stageRules);
        for (RunMode mode : allowedRunModes) {
            CanonicalHashing.appendFramed(canonical, "runMode", mode.name());
        }
        for (StageCommandReference command : commandReferences) {
            appendReference(canonical, "command", command.getIdentifier(),
                    command.getVersion(), command.getContentHash(), false);
        }
        for (StageSkillReference skill : skillReferences) {
            appendReference(canonical, "skill", skill.getIdentifier(),
                    skill.getVersion(), skill.getPackageHash(), skill.isRequired());
        }
        for (StageMcpServerReference mcp : mcpServerReferences) {
            appendReference(canonical, "mcp", mcp.getIdentifier(),
                    mcp.getVersion(), mcp.getDefinitionHash(), mcp.isRequired());
            CanonicalHashing.appendFramed(
                    canonical, "mcpAccess", mcp.getMaximumAccess().name());
            CanonicalHashing.appendFramed(canonical, "mcpTransport", mcp.getTransport());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private void appendReference(
            StringBuilder canonical, String kind, String identifier,
            String version, String hash, boolean required) {
        CanonicalHashing.appendFramed(canonical, kind + "Identifier", identifier);
        CanonicalHashing.appendFramed(canonical, kind + "Version", version);
        CanonicalHashing.appendFramed(canonical, kind + "Hash", hash);
        CanonicalHashing.appendFramed(canonical, kind + "Required", required);
    }
}
