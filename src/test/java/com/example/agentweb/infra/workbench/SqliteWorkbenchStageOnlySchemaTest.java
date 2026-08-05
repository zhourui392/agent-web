package com.example.agentweb.infra.workbench;

import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench 新库只暴露 Stage 持久化模型。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchStageOnlySchemaTest {

    @TempDir
    Path tempDirectory;

    @Test
    void should_CreateOnlyStageWorkbenchTables() throws Exception {
        JdbcTemplate jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDirectory.resolve("stage-only-schema.db"));

        List<String> requiredTables = Arrays.asList(
                "workbench", "workbench_stage",
                "workbench_stage_conversation",
                "workbench_stage_run_snapshot",
                "workbench_stage_run_prompt_payload",
                "workbench_stage_uploaded_attachment",
                "workbench_stage_conversation_restart_receipt");
        List<String> forbiddenTables = Arrays.asList(
                "workbench_phase", "workbench_phase_conversation",
                "workbench_phase_handoff",
                "workbench_phase_handoff_revision",
                "workbench_handoff_reception",
                "workbench_phase_capability_config",
                "workbench_phase_capability_profile",
                "workbench_review_opinion",
                "workbench_review_modify_confirmation",
                "workbench_run_snapshot",
                "workbench_run_prompt_payload",
                "workbench_uploaded_attachment",
                "workbench_phase_conversation_restart_receipt",
                "workbench_high_impact_operation",
                "workbench_high_impact_operation_proposal");

        for (String table : requiredTables) {
            assertEquals(1, tableCount(jdbc, table), table);
        }
        for (String table : forbiddenTables) {
            assertEquals(0, tableCount(jdbc, table), table);
        }
        String chatSessionDefinition = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master "
                        + "WHERE type='table' AND name='chat_session'",
                String.class);
        assertTrue(chatSessionDefinition.contains("WORKBENCH_STAGE"));
        assertFalse(chatSessionDefinition.contains("WORKBENCH_PHASE"));
    }

    @Test
    void should_RejectOldWorkbenchSessionSchemaWithoutMigration() {
        // Given
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDirectory.resolve(
                "old-workbench-session-schema.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_session ("
                + "id TEXT PRIMARY KEY, agent_type TEXT NOT NULL, "
                + "working_dir TEXT NOT NULL, created_at TEXT NOT NULL, "
                + "session_kind TEXT NOT NULL DEFAULT 'CHAT', "
                + "context_id TEXT, retired_at TEXT, "
                + "CHECK (session_kind IN ('CHAT', 'WORKBENCH_PHASE'))) ");

        // When
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new SqliteInitializer(jdbc).init());

        // Then
        assertTrue(failure.getMessage().contains("Stage-only"));
        String definition = chatSessionDefinition(jdbc);
        assertTrue(definition.contains("WORKBENCH_PHASE"));
        assertFalse(definition.contains("WORKBENCH_STAGE"));
    }

    private String chatSessionDefinition(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT sql FROM sqlite_master "
                        + "WHERE type='table' AND name='chat_session'",
                String.class);
    }

    private int tableCount(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master "
                        + "WHERE type='table' AND name=?",
                Integer.class, table);
        return count == null ? 0 : count.intValue();
    }
}
