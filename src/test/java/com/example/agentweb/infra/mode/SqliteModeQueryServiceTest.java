package com.example.agentweb.infra.mode;

import com.example.agentweb.app.mode.AdminModeView;
import com.example.agentweb.app.mode.ModeQueryService;
import com.example.agentweb.domain.capability.CommandCatalog;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.SkillCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 模式读侧 SQLite 查询回归（真实 SQLite）：管理员全量模式列表。
 *
 * @author zhourui
 * @since 2026/08/22
 */
class SqliteModeQueryServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteModeQueryService queryService;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("mode-query-test.db").toAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chat_mode (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, "
                + "identifier TEXT NOT NULL, display_name TEXT NOT NULL, description TEXT, "
                + "default_prompt TEXT, permission_mode TEXT, model TEXT, effort TEXT, "
                + "source_revision_id INTEGER, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, "
                + "version INTEGER NOT NULL DEFAULT 0, UNIQUE (user_id, identifier))");
        jdbc.execute("CREATE TABLE chat_mode_command (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "mode_id TEXT NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (mode_id, capability_identifier))");
        jdbc.execute("CREATE TABLE chat_mode_skill (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "mode_id TEXT NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (mode_id, capability_identifier))");
        jdbc.execute("CREATE TABLE chat_mode_mcp_server (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "mode_id TEXT NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "maximum_access TEXT NOT NULL, transport TEXT NOT NULL, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (mode_id, capability_identifier))");
        jdbc.execute("CREATE TABLE user_account (id TEXT PRIMARY KEY, username TEXT NOT NULL)");
        queryService = new SqliteModeQueryService(jdbc,
                new SqliteChatModeRepository(jdbc),
                mock(CommandCatalog.class), mock(SkillCatalog.class), mock(McpServerCatalog.class));
    }

    private void saveMode(String id, String userId, String identifier,
                          String displayName, Instant updatedAt) {
        jdbc.update("INSERT INTO chat_mode (id, user_id, identifier, display_name, "
                        + "created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, userId, identifier, displayName,
                updatedAt.toString(), updatedAt.toString());
    }

    @Test
    void listAll_returnsEveryUserModeWithOwnerOrderedByUpdatedAtDescending() {
        saveMode("m1", "u1", "reviewer", "评审模式",
                Instant.parse("2026-08-20T10:00:00Z"));
        saveMode("m2", "u2", "coder", "编码模式",
                Instant.parse("2026-08-21T09:00:00Z"));
        jdbc.update("INSERT INTO user_account (id, username) VALUES ('u1', 'alice')");
        jdbc.update("INSERT INTO user_account (id, username) VALUES ('u2', 'bob')");

        List<AdminModeView> modes = queryService.listAll();

        assertEquals(2, modes.size());
        assertEquals("m2", modes.get(0).id());
        assertEquals("bob", modes.get(0).ownerUsername());
        assertEquals("u2", modes.get(0).ownerUserId());
        assertEquals("编码模式", modes.get(0).displayName());
        assertEquals("m1", modes.get(1).id());
        assertEquals("alice", modes.get(1).ownerUsername());
    }

    @Test
    void listAll_keepsModeWhenOwnerAccountMissing() {
        saveMode("m3", "ghost", "orphan", "孤儿模式",
                Instant.parse("2026-08-19T08:00:00Z"));

        List<AdminModeView> modes = queryService.listAll();

        assertEquals(1, modes.size());
        assertEquals("ghost", modes.get(0).ownerUserId());
        assertEquals("", modes.get(0).ownerUsername());
    }

    @Test
    void findTemplateRevision_returnsStageRulesForFork() {
        jdbc.execute("CREATE TABLE workbench_stage_definition ("
                + "definition_identifier TEXT PRIMARY KEY, "
                + "current_published_revision INTEGER, disabled INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE workbench_stage_definition_revision ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "sequence_number INTEGER NOT NULL, display_name TEXT NOT NULL, "
                + "description TEXT NOT NULL, stage_rules TEXT NOT NULL, "
                + "allowed_run_modes_json TEXT NOT NULL DEFAULT '[]', "
                + "definition_hash TEXT NOT NULL DEFAULT '', "
                + "created_by_id TEXT NOT NULL DEFAULT '', created_by_name TEXT NOT NULL DEFAULT '', "
                + "created_at INTEGER NOT NULL DEFAULT 0, published_at INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(definition_identifier, revision_number))");
        jdbc.execute("CREATE TABLE workbench_stage_definition_command ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "command_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE workbench_stage_definition_skill ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "skill_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE workbench_stage_definition_mcp_server ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "mcp_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "maximum_access TEXT NOT NULL DEFAULT 'READ', "
                + "transport TEXT NOT NULL DEFAULT 'STDIO')");
        jdbc.update("INSERT INTO workbench_stage_definition "
                + "(definition_identifier, current_published_revision, disabled) "
                + "VALUES ('builtin-1-requirement-analysis', 1, 0)");
        jdbc.update("INSERT INTO workbench_stage_definition_revision "
                + "(definition_identifier, revision_number, sequence_number, display_name, "
                + "description, stage_rules) VALUES "
                + "('builtin-1-requirement-analysis', 1, 90, '需求分析（DDD）', '按 DDD 梳理需求', "
                + "'按 DDD 方式对需求进行一次梳理')");

        ModeQueryService.ModeTemplateRevisionView revision =
                queryService.findTemplateRevision("builtin-1-requirement-analysis", 1L);

        assertEquals("builtin-1-requirement-analysis", revision.definitionIdentifier());
        assertEquals("按 DDD 方式对需求进行一次梳理", revision.stageRules());
    }

    @Test
    void findTemplateRevision_filtersChildrenByDefinitionIdentifier() {
        jdbc.execute("CREATE TABLE workbench_stage_definition ("
                + "definition_identifier TEXT PRIMARY KEY, "
                + "current_published_revision INTEGER, disabled INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE workbench_stage_definition_revision ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "sequence_number INTEGER NOT NULL, display_name TEXT NOT NULL, "
                + "description TEXT NOT NULL, stage_rules TEXT NOT NULL, "
                + "allowed_run_modes_json TEXT NOT NULL DEFAULT '[]', "
                + "definition_hash TEXT NOT NULL DEFAULT '', "
                + "created_by_id TEXT NOT NULL DEFAULT '', created_by_name TEXT NOT NULL DEFAULT '', "
                + "created_at INTEGER NOT NULL DEFAULT 0, published_at INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(definition_identifier, revision_number))");
        jdbc.execute("CREATE TABLE workbench_stage_definition_command ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "command_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE workbench_stage_definition_skill ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "skill_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE workbench_stage_definition_mcp_server ("
                + "definition_identifier TEXT NOT NULL, revision_number INTEGER NOT NULL, "
                + "mcp_order INTEGER NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "maximum_access TEXT NOT NULL DEFAULT 'READ', "
                + "transport TEXT NOT NULL DEFAULT 'STDIO')");
        Object[][] revisions = {
                {"builtin-1-requirement-analysis", 1},
                {"builtin-3-dev-verify", 1}};
        for (Object[] revision : revisions) {
            jdbc.update("INSERT INTO workbench_stage_definition "
                    + "(definition_identifier, current_published_revision, disabled) "
                    + "VALUES (?, ?, 0)", revision[0], revision[1]);
            jdbc.update("INSERT INTO workbench_stage_definition_revision "
                    + "(definition_identifier, revision_number, sequence_number, display_name, "
                    + "description, stage_rules) VALUES (?, ?, 1, 'n', 'd', 'r')",
                    revision[0], revision[1]);
        }
        insertSkill("builtin-1-requirement-analysis", 1, "business-abstraction");
        insertSkill("builtin-3-dev-verify", 1, "java-tdd");

        ModeQueryService.ModeTemplateRevisionView revision =
                queryService.findTemplateRevision("builtin-1-requirement-analysis", 1L);

        assertEquals(1, revision.skills().size());
        assertEquals("business-abstraction", revision.skills().get(0).identifier());
    }

    private void insertSkill(String definitionIdentifier, int revisionNumber,
                             String skillIdentifier) {
        jdbc.update("INSERT INTO workbench_stage_definition_skill "
                        + "(definition_identifier, revision_number, skill_order, "
                        + "capability_identifier, capability_version, capability_hash) "
                        + "VALUES (?, ?, 0, ?, 'v1', 'h')",
                definitionIdentifier, revisionNumber, skillIdentifier);
    }
}
