package com.example.agentweb.infra.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
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
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraft;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Workbench Stage Catalog 的 SQLite 聚合仓储。
 *
 * @author alex
 * @since 2026-08-05
 */
@Repository
public class SqliteWorkbenchStageCatalogRepository
        implements WorkbenchStageCatalogRepository {

    private static final int SINGLETON_ID = 1;

    private final JdbcTemplate jdbcTemplate;
    private final StageDraftJsonMapper jsonMapper;

    public SqliteWorkbenchStageCatalogRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "Stage Catalog JdbcTemplate is required");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = new StageDraftJsonMapper(objectMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkbenchStageCatalog find() {
        List<CatalogRow> catalogs = jdbcTemplate.query(
                "SELECT catalog_version, updated_at FROM workbench_stage_catalog "
                        + "WHERE singleton_id = ?",
                (resultSet, rowNumber) -> new CatalogRow(
                        resultSet.getLong("catalog_version"),
                        nullableInstant(resultSet, "updated_at")),
                SINGLETON_ID);
        if (catalogs.isEmpty()) {
            return WorkbenchStageCatalog.empty();
        }
        CatalogRow catalog = catalogs.get(0);
        List<WorkbenchStageDefinition> definitions = jdbcTemplate.query(
                "SELECT definition_identifier, current_published_revision, disabled, "
                        + "created_by_id, created_by_name, created_at, updated_by_id, "
                        + "updated_by_name, updated_at, version "
                        + "FROM workbench_stage_definition ORDER BY definition_identifier",
                (resultSet, rowNumber) -> restoreDefinition(
                        resultSet.getString("definition_identifier"),
                        nullableLong(resultSet, "current_published_revision"),
                        resultSet.getInt("disabled") != 0,
                        StageCatalogEditor.create(
                                resultSet.getString("created_by_id"),
                                resultSet.getString("created_by_name")),
                        Instant.ofEpochMilli(resultSet.getLong("created_at")),
                        StageCatalogEditor.create(
                                resultSet.getString("updated_by_id"),
                                resultSet.getString("updated_by_name")),
                        Instant.ofEpochMilli(resultSet.getLong("updated_at")),
                        resultSet.getLong("version")));
        return WorkbenchStageCatalog.restore(
                catalog.catalogVersion, catalog.updatedAt, definitions);
    }

    @Override
    @Transactional
    public void save(
            WorkbenchStageCatalog catalog, long expectedCatalogVersion,
            String changedDefinitionIdentifier,
            long expectedDefinitionVersion) {
        if (catalog == null || changedDefinitionIdentifier == null) {
            throw new IllegalArgumentException(
                    "Stage Catalog and changed Definition are required");
        }
        WorkbenchStageDefinition definition =
                catalog.requireDefinition(changedDefinitionIdentifier);
        requireCatalogState(expectedCatalogVersion);
        persistDefinition(definition, expectedDefinitionVersion);
        persistDraft(definition);
        persistRevisions(definition);
        persistCatalog(catalog, expectedCatalogVersion);
    }

    private WorkbenchStageDefinition restoreDefinition(
            String identifier, Long currentRevisionNumber, boolean disabled,
            StageCatalogEditor createdBy, Instant createdAt,
            StageCatalogEditor updatedBy, Instant updatedAt, long version) {
        List<WorkbenchStageDefinitionRevision> revisions = revisions(identifier);
        WorkbenchStageDefinitionRevision current = currentRevision(
                currentRevisionNumber, revisions);
        WorkbenchStageDraft draft = draft(identifier);
        return WorkbenchStageDefinition.restore(
                identifier, draft, current, revisions, disabled,
                createdBy, createdAt, updatedBy, updatedAt, version);
    }

    private WorkbenchStageDraft draft(String identifier) {
        List<WorkbenchStageDraft> drafts = jdbcTemplate.query(
                "SELECT based_on_published_revision, draft_content_json, draft_hash, "
                        + "saved_by_id, saved_by_name, saved_at "
                        + "FROM workbench_stage_draft WHERE definition_identifier = ?",
                (resultSet, rowNumber) -> WorkbenchStageDraft.restore(
                        nullableLong(resultSet, "based_on_published_revision"),
                        jsonMapper.draft(resultSet.getString("draft_content_json")),
                        resultSet.getString("draft_hash"),
                        StageCatalogEditor.create(
                                resultSet.getString("saved_by_id"),
                                resultSet.getString("saved_by_name")),
                        Instant.ofEpochMilli(resultSet.getLong("saved_at"))),
                identifier);
        return drafts.stream().findFirst().orElse(null);
    }

    private List<WorkbenchStageDefinitionRevision> revisions(String identifier) {
        return jdbcTemplate.query(
                "SELECT revision_number, sequence_number, display_name, description, "
                        + "stage_rules, allowed_run_modes_json, definition_hash, "
                        + "created_by_id, created_by_name, published_at "
                        + "FROM workbench_stage_definition_revision "
                        + "WHERE definition_identifier = ? ORDER BY revision_number",
                (resultSet, rowNumber) -> {
                    long revisionNumber = resultSet.getLong("revision_number");
                    ResolvedStageCapabilities capabilities = capabilities(
                            identifier, revisionNumber);
                    WorkbenchStageDraftContent content =
                            WorkbenchStageDraftContent.create(
                                    resultSet.getInt("sequence_number"),
                                    resultSet.getString("display_name"),
                                    resultSet.getString("description"),
                                    resultSet.getString("stage_rules"),
                                    jsonMapper.runModes(resultSet.getString(
                                            "allowed_run_modes_json")),
                                    commandSelections(capabilities),
                                    skillSelections(capabilities),
                                    mcpSelections(capabilities));
                    return WorkbenchStageDefinitionRevision.restore(
                            identifier, revisionNumber, content, capabilities,
                            resultSet.getString("definition_hash"),
                            StageCatalogEditor.create(
                                    resultSet.getString("created_by_id"),
                                    resultSet.getString("created_by_name")),
                            Instant.ofEpochMilli(resultSet.getLong("published_at")));
                }, identifier);
    }

    private ResolvedStageCapabilities capabilities(
            String identifier, long revisionNumber) {
        List<StageCommandReference> commands = jdbcTemplate.query(
                "SELECT capability_identifier, capability_version, capability_hash "
                        + "FROM workbench_stage_definition_command "
                        + "WHERE definition_identifier = ? AND revision_number = ? "
                        + "ORDER BY command_order",
                (resultSet, rowNumber) -> new StageCommandReference(
                        resultSet.getString("capability_identifier"),
                        resultSet.getString("capability_version"),
                        resultSet.getString("capability_hash")),
                identifier, revisionNumber);
        List<StageSkillReference> skills = jdbcTemplate.query(
                "SELECT capability_identifier, capability_version, capability_hash, required "
                        + "FROM workbench_stage_definition_skill "
                        + "WHERE definition_identifier = ? AND revision_number = ? "
                        + "ORDER BY skill_order",
                (resultSet, rowNumber) -> new StageSkillReference(
                        resultSet.getString("capability_identifier"),
                        resultSet.getString("capability_version"),
                        resultSet.getString("capability_hash"),
                        resultSet.getInt("required") != 0),
                identifier, revisionNumber);
        List<StageMcpServerReference> mcpServers = jdbcTemplate.query(
                "SELECT capability_identifier, capability_version, capability_hash, "
                        + "required, maximum_access, transport "
                        + "FROM workbench_stage_definition_mcp_server "
                        + "WHERE definition_identifier = ? AND revision_number = ? "
                        + "ORDER BY mcp_order",
                (resultSet, rowNumber) -> new StageMcpServerReference(
                        resultSet.getString("capability_identifier"),
                        resultSet.getString("capability_version"),
                        resultSet.getString("capability_hash"),
                        resultSet.getInt("required") != 0,
                        CapabilityAccess.valueOf(resultSet.getString("maximum_access")),
                        resultSet.getString("transport")),
                identifier, revisionNumber);
        return new ResolvedStageCapabilities(commands, skills, mcpServers);
    }

    private List<StageCommandSelection> commandSelections(
            ResolvedStageCapabilities capabilities) {
        List<StageCommandSelection> selections =
                new ArrayList<StageCommandSelection>();
        for (StageCommandReference reference : capabilities.getCommands()) {
            selections.add(new StageCommandSelection(
                    reference.getIdentifier(), reference.getVersion()));
        }
        return selections;
    }

    private List<StageSkillSelection> skillSelections(
            ResolvedStageCapabilities capabilities) {
        List<StageSkillSelection> selections = new ArrayList<StageSkillSelection>();
        for (StageSkillReference reference : capabilities.getSkills()) {
            selections.add(new StageSkillSelection(reference.getIdentifier(),
                    reference.getVersion(), reference.isRequired()));
        }
        return selections;
    }

    private List<StageMcpServerSelection> mcpSelections(
            ResolvedStageCapabilities capabilities) {
        List<StageMcpServerSelection> selections =
                new ArrayList<StageMcpServerSelection>();
        for (StageMcpServerReference reference : capabilities.getMcpServers()) {
            selections.add(new StageMcpServerSelection(reference.getIdentifier(),
                    reference.getVersion(), reference.isRequired()));
        }
        return selections;
    }

    private WorkbenchStageDefinitionRevision currentRevision(
            Long currentRevisionNumber,
            List<WorkbenchStageDefinitionRevision> revisions) {
        if (currentRevisionNumber == null) {
            return null;
        }
        for (WorkbenchStageDefinitionRevision revision : revisions) {
            if (revision.getRevisionNumber() == currentRevisionNumber.longValue()) {
                return revision;
            }
        }
        throw new IllegalStateException(
                "persisted current Stage Revision does not exist");
    }

    private void requireCatalogState(long expectedCatalogVersion) {
        List<Long> versions = jdbcTemplate.query(
                "SELECT catalog_version FROM workbench_stage_catalog WHERE singleton_id = ?",
                (resultSet, rowNumber) -> resultSet.getLong(1), SINGLETON_ID);
        if (!versions.isEmpty() && versions.get(0).longValue() != expectedCatalogVersion) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_CATALOG_VERSION_CONFLICT",
                    "Stage Catalog version has changed");
        }
        if (versions.isEmpty() && expectedCatalogVersion != 1L) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_CATALOG_VERSION_CONFLICT",
                    "Stage Catalog version has changed");
        }
    }

    private void persistDefinition(
            WorkbenchStageDefinition definition, long expectedDefinitionVersion) {
        if (expectedDefinitionVersion == 0L) {
            int inserted = jdbcTemplate.update(
                    "INSERT OR IGNORE INTO workbench_stage_definition "
                            + "(definition_identifier, current_published_revision, disabled, "
                            + "created_by_id, created_by_name, created_at, updated_by_id, "
                            + "updated_by_name, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    definitionArguments(definition));
            if (inserted != 1) {
                throw definitionVersionConflict();
            }
            return;
        }
        List<Object> arguments = new ArrayList<Object>();
        Long currentRevision = definition.getCurrentPublishedRevision() == null
                ? null : Long.valueOf(
                definition.getCurrentPublishedRevision().getRevisionNumber());
        Collections.addAll(arguments, currentRevision,
                definition.isDisabled() ? 1 : 0,
                definition.getCreatedBy().getActorId(),
                definition.getCreatedBy().getActorName(),
                definition.getCreatedAt().toEpochMilli(),
                definition.getUpdatedBy().getActorId(),
                definition.getUpdatedBy().getActorName(),
                definition.getUpdatedAt().toEpochMilli(), definition.getVersion());
        arguments.add(definition.getDefinitionIdentifier());
        arguments.add(expectedDefinitionVersion);
        int changed = jdbcTemplate.update(
                "UPDATE workbench_stage_definition SET "
                        + "current_published_revision = ?, disabled = ?, created_by_id = ?, "
                        + "created_by_name = ?, created_at = ?, updated_by_id = ?, "
                        + "updated_by_name = ?, updated_at = ?, version = ? "
                        + "WHERE definition_identifier = ? AND version = ?",
                arguments.toArray());
        if (changed != 1) {
            throw definitionVersionConflict();
        }
    }

    private Object[] definitionArguments(WorkbenchStageDefinition definition) {
        Long currentRevision = definition.getCurrentPublishedRevision() == null
                ? null : Long.valueOf(
                definition.getCurrentPublishedRevision().getRevisionNumber());
        return new Object[]{definition.getDefinitionIdentifier(), currentRevision,
                definition.isDisabled() ? 1 : 0,
                definition.getCreatedBy().getActorId(),
                definition.getCreatedBy().getActorName(),
                definition.getCreatedAt().toEpochMilli(),
                definition.getUpdatedBy().getActorId(),
                definition.getUpdatedBy().getActorName(),
                definition.getUpdatedAt().toEpochMilli(), definition.getVersion()};
    }

    private void persistDraft(WorkbenchStageDefinition definition) {
        jdbcTemplate.update("DELETE FROM workbench_stage_draft "
                        + "WHERE definition_identifier = ?",
                definition.getDefinitionIdentifier());
        if (!definition.hasDraft()) {
            return;
        }
        WorkbenchStageDraft draft = definition.getDraft();
        jdbcTemplate.update(
                "INSERT INTO workbench_stage_draft "
                        + "(definition_identifier, based_on_published_revision, "
                        + "draft_content_json, draft_hash, saved_by_id, saved_by_name, "
                        + "saved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                definition.getDefinitionIdentifier(),
                draft.getBasedOnPublishedRevisionNumber(),
                jsonMapper.draft(draft.getContent()), draft.getDraftHash(),
                draft.getSavedBy().getActorId(), draft.getSavedBy().getActorName(),
                draft.getSavedAt().toEpochMilli());
    }

    private void persistRevisions(WorkbenchStageDefinition definition) {
        for (WorkbenchStageDefinitionRevision revision
                : definition.getRevisionHistory()) {
            int inserted = jdbcTemplate.update(
                    "INSERT OR IGNORE INTO workbench_stage_definition_revision "
                            + "(definition_identifier, revision_number, sequence_number, "
                            + "display_name, description, stage_rules, allowed_run_modes_json, "
                            + "definition_hash, created_by_id, created_by_name, created_at, "
                            + "published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    definition.getDefinitionIdentifier(), revision.getRevisionNumber(),
                    revision.getSequenceNumber(), revision.getDisplayName(),
                    revision.getDescription(), revision.getStageRules(),
                    jsonMapper.runModes(revision.getAllowedRunModes()),
                    revision.getDefinitionHash(), revision.getCreatedBy().getActorId(),
                    revision.getCreatedBy().getActorName(),
                    revision.getCreatedAt().toEpochMilli(),
                    revision.getPublishedAt().toEpochMilli());
            if (inserted == 1) {
                persistRevisionCapabilities(definition.getDefinitionIdentifier(), revision);
            } else {
                requireStoredRevisionHash(definition.getDefinitionIdentifier(), revision);
            }
        }
    }

    private void persistRevisionCapabilities(
            String identifier, WorkbenchStageDefinitionRevision revision) {
        int order = 0;
        for (StageCommandReference command : revision.getCommandReferences()) {
            jdbcTemplate.update("INSERT INTO workbench_stage_definition_command "
                            + "(definition_identifier, revision_number, command_order, "
                            + "capability_identifier, capability_version, capability_hash) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    identifier, revision.getRevisionNumber(), order++,
                    command.getIdentifier(), command.getVersion(), command.getContentHash());
        }
        order = 0;
        for (StageSkillReference skill : revision.getSkillReferences()) {
            jdbcTemplate.update("INSERT INTO workbench_stage_definition_skill "
                            + "(definition_identifier, revision_number, skill_order, "
                            + "capability_identifier, capability_version, capability_hash, "
                            + "required) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    identifier, revision.getRevisionNumber(), order++,
                    skill.getIdentifier(), skill.getVersion(), skill.getPackageHash(),
                    skill.isRequired() ? 1 : 0);
        }
        order = 0;
        for (StageMcpServerReference mcpServer : revision.getMcpServerReferences()) {
            jdbcTemplate.update("INSERT INTO workbench_stage_definition_mcp_server "
                            + "(definition_identifier, revision_number, mcp_order, "
                            + "capability_identifier, capability_version, capability_hash, "
                            + "required, maximum_access, transport) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    identifier, revision.getRevisionNumber(), order++,
                    mcpServer.getIdentifier(), mcpServer.getVersion(),
                    mcpServer.getDefinitionHash(), mcpServer.isRequired() ? 1 : 0,
                    mcpServer.getMaximumAccess().name(), mcpServer.getTransport());
        }
    }

    private void requireStoredRevisionHash(
            String identifier, WorkbenchStageDefinitionRevision revision) {
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT definition_hash FROM workbench_stage_definition_revision "
                        + "WHERE definition_identifier = ? AND revision_number = ?",
                String.class, identifier, revision.getRevisionNumber());
        if (!revision.getDefinitionHash().equals(storedHash)) {
            throw new IllegalStateException(
                    "persisted Stage Revision was modified after publication");
        }
    }

    private void persistCatalog(
            WorkbenchStageCatalog catalog, long expectedCatalogVersion) {
        List<Long> versions = jdbcTemplate.query(
                "SELECT catalog_version FROM workbench_stage_catalog WHERE singleton_id = ?",
                (resultSet, rowNumber) -> resultSet.getLong(1), SINGLETON_ID);
        if (versions.isEmpty()) {
            int inserted = jdbcTemplate.update(
                    "INSERT OR IGNORE INTO workbench_stage_catalog "
                            + "(singleton_id, catalog_version, updated_at) VALUES (?, ?, ?)",
                    SINGLETON_ID, catalog.getCatalogVersion(),
                    catalog.getUpdatedAt().toEpochMilli());
            if (inserted != 1) {
                throw catalogVersionConflict();
            }
            return;
        }
        if (catalog.getCatalogVersion() != expectedCatalogVersion
                && catalog.getCatalogVersion() != expectedCatalogVersion + 1L) {
            throw catalogVersionConflict();
        }
        int changed = jdbcTemplate.update(
                "UPDATE workbench_stage_catalog SET catalog_version = ?, updated_at = ? "
                        + "WHERE singleton_id = ? AND catalog_version = ?",
                catalog.getCatalogVersion(), catalog.getUpdatedAt().toEpochMilli(),
                SINGLETON_ID, expectedCatalogVersion);
        if (changed != 1) {
            throw catalogVersionConflict();
        }
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Long.valueOf(value);
    }

    private Instant nullableInstant(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private StageCatalogException definitionVersionConflict() {
        return new StageCatalogException(
                "WORKBENCH_STAGE_DEFINITION_VERSION_CONFLICT",
                "Stage Definition version has changed");
    }

    private StageCatalogException catalogVersionConflict() {
        return new StageCatalogException(
                "WORKBENCH_STAGE_CATALOG_VERSION_CONFLICT",
                "Stage Catalog version has changed");
    }

    private static final class CatalogRow {
        private final long catalogVersion;
        private final Instant updatedAt;

        private CatalogRow(long catalogVersion, Instant updatedAt) {
            this.catalogVersion = catalogVersion;
            this.updatedAt = updatedAt;
        }
    }
}
