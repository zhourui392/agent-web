package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.RunMode;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Stage 一次已发布且不可变的定义 Revision。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageDefinitionRevision {

    private final String definitionIdentifier;
    private final long revisionNumber;
    private final int sequenceNumber;
    private final String displayName;
    private final String description;
    private final String stageRules;
    private final Set<RunMode> allowedRunModes;
    private final List<StageCommandReference> commandReferences;
    private final List<StageSkillReference> skillReferences;
    private final List<StageMcpServerReference> mcpServerReferences;
    private final String definitionHash;
    private final StageCatalogEditor createdBy;
    private final Instant createdAt;
    private final Instant publishedAt;

    private WorkbenchStageDefinitionRevision(
            String definitionIdentifier, long revisionNumber,
            WorkbenchStageDraftContent draft,
            ResolvedStageCapabilities resolved,
            StageCatalogEditor editor, Instant publishedAt) {
        this.definitionIdentifier = DomainText.require(
                definitionIdentifier, "Stage Definition identifier", 128);
        if (revisionNumber < 1L || draft == null || resolved == null || editor == null) {
            throw new IllegalArgumentException("Published Stage Revision facts are required");
        }
        this.revisionNumber = revisionNumber;
        this.sequenceNumber = draft.getSequenceNumber();
        this.displayName = draft.getDisplayName();
        this.description = draft.getDescription();
        this.stageRules = draft.getStageRules();
        this.allowedRunModes = draft.getAllowedRunModes();
        this.commandReferences = resolved.getCommands();
        this.skillReferences = resolved.getSkills();
        this.mcpServerReferences = resolved.getMcpServers();
        this.createdBy = editor;
        this.createdAt = DomainText.requireTime(
                publishedAt, "Stage Revision creation time");
        this.publishedAt = this.createdAt;
        this.definitionHash = calculateDefinitionHash();
    }

    static WorkbenchStageDefinitionRevision publish(
            String definitionIdentifier, long revisionNumber,
            WorkbenchStageDraftContent draft,
            ResolvedStageCapabilities resolved,
            StageCatalogEditor editor, Instant publishedAt) {
        draft.requireResolvedCapabilities(resolved);
        return new WorkbenchStageDefinitionRevision(
                definitionIdentifier, revisionNumber, draft,
                resolved, editor, publishedAt);
    }

    public static WorkbenchStageDefinitionRevision restore(
            String definitionIdentifier, long revisionNumber,
            WorkbenchStageDraftContent content,
            ResolvedStageCapabilities resolved, String expectedDefinitionHash,
            StageCatalogEditor createdBy, Instant publishedAt) {
        content.requireResolvedCapabilities(resolved);
        WorkbenchStageDefinitionRevision restored =
                new WorkbenchStageDefinitionRevision(
                        definitionIdentifier, revisionNumber, content,
                        resolved, createdBy, publishedAt);
        if (!restored.definitionHash.equals(expectedDefinitionHash)) {
            throw new IllegalStateException(
                    "persisted Stage Revision Hash does not match its content");
        }
        return restored;
    }

    private String calculateDefinitionHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", "workbench-stage-revision@1");
        CanonicalHashing.appendFramed(canonical, "definitionIdentifier",
                definitionIdentifier);
        CanonicalHashing.appendFramed(canonical, "revisionNumber", revisionNumber);
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
        for (StageMcpServerReference mcpServer : mcpServerReferences) {
            appendReference(canonical, "mcp", mcpServer.getIdentifier(),
                    mcpServer.getVersion(), mcpServer.getDefinitionHash(),
                    mcpServer.isRequired());
            CanonicalHashing.appendFramed(canonical, "mcpAccess",
                    mcpServer.getMaximumAccess().name());
            CanonicalHashing.appendFramed(canonical, "mcpTransport",
                    mcpServer.getTransport());
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
