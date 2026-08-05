package com.example.agentweb.infra.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.StageCatalogException;
import com.example.agentweb.domain.workbench.stage.StageCommandReference;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageLifecycleStatus;
import com.example.agentweb.domain.workbench.stage.StageMcpServerReference;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinition;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench Stage Catalog SQLite 仓储集成测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchStageCatalogRepositoryTest {

    private static final StageCatalogEditor ADMINISTRATOR =
            StageCatalogEditor.create("admin-1", "Alex");
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-05T08:00:00Z");

    @TempDir
    Path tempDirectory;

    private JdbcTemplate jdbcTemplate;
    private SqliteWorkbenchStageCatalogRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDirectory.resolve("stage-catalog.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        createTables();
        repository = new SqliteWorkbenchStageCatalogRepository(
                jdbcTemplate, new ObjectMapper());
    }

    @Test
    void should_RoundTripDraftPublishedRevisionAndNewDraft() {
        // Given
        WorkbenchStageCatalog catalog = repository.find();
        catalog.createDraft("solution-design", draft(20, "技术方案"),
                ADMINISTRATOR, CREATED_AT);
        repository.save(catalog, 1L, "solution-design", 0L);
        WorkbenchStageCatalog withDraft = repository.find();
        WorkbenchStageDefinition draftDefinition =
                withDraft.requireDefinition("solution-design");
        withDraft.publishDraft("solution-design", withDraft.getCatalogVersion(),
                draftDefinition.getVersion(), resolvedCapabilities(),
                ADMINISTRATOR, CREATED_AT.plusSeconds(60));
        repository.save(withDraft, 1L, "solution-design", 1L);
        WorkbenchStageCatalog published = repository.find();
        WorkbenchStageDefinition publishedDefinition =
                published.requireDefinition("solution-design");
        published.saveDraft("solution-design", publishedDefinition.getVersion(),
                draft(25, "技术方案 v2"), ADMINISTRATOR,
                CREATED_AT.plusSeconds(120));

        // When
        repository.save(published, 2L, "solution-design", 2L);
        WorkbenchStageDefinition restored = repository.find()
                .requireDefinition("solution-design");

        // Then
        assertEquals(StageLifecycleStatus.PUBLISHED, restored.getLifecycleStatus());
        assertTrue(restored.hasDraft());
        assertEquals("技术方案", restored.getCurrentPublishedRevision().getDisplayName());
        assertEquals("技术方案 v2", restored.getDraft().getContent().getDisplayName());
        assertEquals(1, restored.getRevisionHistory().size());
        assertEquals(3L, restored.getVersion());
    }

    @Test
    void should_PersistDisableWithoutRemovingPublishedHistory() {
        // Given
        WorkbenchStageCatalog catalog = publishedCatalog();
        WorkbenchStageDefinition definition =
                catalog.requireDefinition("solution-design");

        // When
        catalog.disable("solution-design", catalog.getCatalogVersion(),
                definition.getVersion(), ADMINISTRATOR,
                CREATED_AT.plusSeconds(120));
        repository.save(catalog, 2L, "solution-design", 2L);
        WorkbenchStageDefinition restored = repository.find()
                .requireDefinition("solution-design");

        // Then
        assertEquals(StageLifecycleStatus.DISABLED, restored.getLifecycleStatus());
        assertFalse(restored.getRevisionHistory().isEmpty());
        assertTrue(repository.find().selectableRevisions().isEmpty());
    }

    @Test
    void should_RejectStaleDefinitionCompareAndSet() {
        // Given
        WorkbenchStageCatalog first = publishedCatalog();
        WorkbenchStageCatalog stale = repository.find();
        first.saveDraft("solution-design", 2L, draft(25, "first edit"),
                ADMINISTRATOR, CREATED_AT.plusSeconds(120));
        repository.save(first, 2L, "solution-design", 2L);
        stale.saveDraft("solution-design", 2L, draft(30, "stale edit"),
                ADMINISTRATOR, CREATED_AT.plusSeconds(180));

        // When / Then
        StageCatalogException failure = assertThrows(StageCatalogException.class,
                () -> repository.save(stale, 2L, "solution-design", 2L));
        assertEquals("WORKBENCH_STAGE_DEFINITION_VERSION_CONFLICT", failure.getCode());
    }

    @Test
    void should_FailClosed_When_PublishedRevisionHashIsCorrupted() {
        // Given
        publishedCatalog();
        jdbcTemplate.update("UPDATE workbench_stage_definition_revision "
                + "SET definition_hash = ? WHERE definition_identifier = ?",
                "0".repeat(64), "solution-design");

        // When / Then
        assertThrows(IllegalStateException.class, repository::find);
    }

    private WorkbenchStageCatalog publishedCatalog() {
        WorkbenchStageCatalog catalog = repository.find();
        catalog.createDraft("solution-design", draft(20, "技术方案"),
                ADMINISTRATOR, CREATED_AT);
        repository.save(catalog, 1L, "solution-design", 0L);
        WorkbenchStageCatalog saved = repository.find();
        saved.publishDraft("solution-design", saved.getCatalogVersion(),
                saved.requireDefinition("solution-design").getVersion(),
                resolvedCapabilities(), ADMINISTRATOR,
                CREATED_AT.plusSeconds(60));
        repository.save(saved, 1L, "solution-design", 1L);
        return repository.find();
    }

    private WorkbenchStageDraftContent draft(int sequenceNumber, String name) {
        return WorkbenchStageDraftContent.create(
                sequenceNumber, name, "阶段说明", "遵循阶段规则",
                Set.of(RunMode.DISCUSS_READ_ONLY),
                Collections.singletonList(new StageCommandSelection(
                        "architecture-review", "1.0.0")),
                Collections.emptyList(),
                Collections.singletonList(new StageMcpServerSelection(
                        "repository-query", "1.0.0", false)));
    }

    private ResolvedStageCapabilities resolvedCapabilities() {
        return new ResolvedStageCapabilities(
                Collections.singletonList(new StageCommandReference(
                        "architecture-review", "1.0.0",
                        CanonicalHashing.sha256("command"))),
                Collections.emptyList(),
                Collections.singletonList(new StageMcpServerReference(
                        "repository-query", "1.0.0",
                        CanonicalHashing.sha256("mcp"), false,
                        CapabilityAccess.READ, "STDIO")));
    }

    private void createTables() {
        jdbcTemplate.execute("CREATE TABLE workbench_stage_catalog ("
                + "singleton_id INTEGER PRIMARY KEY, catalog_version INTEGER NOT NULL,"
                + "updated_at INTEGER)");
        jdbcTemplate.execute("CREATE TABLE workbench_stage_definition ("
                + "definition_identifier TEXT PRIMARY KEY,"
                + "current_published_revision INTEGER, disabled INTEGER NOT NULL,"
                + "created_by_id TEXT NOT NULL, created_by_name TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL, updated_by_id TEXT NOT NULL,"
                + "updated_by_name TEXT NOT NULL, updated_at INTEGER NOT NULL,"
                + "version INTEGER NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE workbench_stage_draft ("
                + "definition_identifier TEXT PRIMARY KEY,"
                + "based_on_published_revision INTEGER, draft_content_json TEXT NOT NULL,"
                + "draft_hash TEXT NOT NULL, saved_by_id TEXT NOT NULL,"
                + "saved_by_name TEXT NOT NULL, saved_at INTEGER NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE workbench_stage_definition_revision ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL,"
                + "sequence_number INTEGER NOT NULL, display_name TEXT NOT NULL,"
                + "description TEXT NOT NULL, stage_rules TEXT NOT NULL,"
                + "allowed_run_modes_json TEXT NOT NULL, definition_hash TEXT NOT NULL,"
                + "created_by_id TEXT NOT NULL, created_by_name TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL, published_at INTEGER NOT NULL,"
                + "PRIMARY KEY(definition_identifier, revision_number))");
        jdbcTemplate.execute("CREATE TABLE workbench_stage_definition_command ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL,"
                + "command_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL,"
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL,"
                + "PRIMARY KEY(definition_identifier, revision_number, command_order))");
        jdbcTemplate.execute("CREATE TABLE workbench_stage_definition_skill ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL,"
                + "skill_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL,"
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL,"
                + "required INTEGER NOT NULL,"
                + "PRIMARY KEY(definition_identifier, revision_number, skill_order))");
        jdbcTemplate.execute("CREATE TABLE workbench_stage_definition_mcp_server ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL,"
                + "mcp_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL,"
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL,"
                + "required INTEGER NOT NULL, maximum_access TEXT NOT NULL,"
                + "transport TEXT NOT NULL,"
                + "PRIMARY KEY(definition_identifier, revision_number, mcp_order))");
    }
}
