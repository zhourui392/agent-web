package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.HarnessHashing;
import com.example.agentweb.domain.harness.HarnessPromptAssembly;
import com.example.agentweb.domain.harness.HarnessPromptPart;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.PromptPartType;
import com.example.agentweb.domain.harness.SkillSelection;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness M2 真实旧表升级到 M3 的幂等迁移测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SqliteHarnessM3MigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldMigrateLegacyM2TablesTwiceWithoutChangingSnapshotHash() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("legacy-m2.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createLegacyTables(jdbc);
        CapabilitySnapshot legacy = legacySnapshot();
        insertLegacySnapshot(jdbc, legacy);

        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();

        assertTrue(columnNames(jdbc, "harness_stage_attempt").contains("snapshot_hash"));
        assertTrue(columnNames(jdbc, "harness_stage_attempt").contains("execution_id"));
        assertTrue(columnNames(jdbc, "harness_capability_snapshot").contains("schema_version"));
        assertTrue(columnNames(jdbc, "harness_capability_snapshot")
                .contains("selected_mcp_servers_json"));
        assertTrue(tableNames(jdbc).contains("harness_runtime_execution"));
        assertTrue(tableNames(jdbc).contains("harness_runtime_event"));

        CapabilitySnapshot restored = new SqliteCapabilitySnapshotRepository(jdbc)
                .find("legacy-run", HarnessStage.ANALYSIS, 1)
                .orElseThrow(AssertionError::new);
        assertEquals(CapabilitySnapshot.SCHEMA_M2, restored.getSchemaVersion());
        assertTrue(restored.getSelectedMcpServers().isEmpty());
        assertEquals(legacy.getSnapshotHash(), restored.getSnapshotHash());
    }

    private void createLegacyTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE harness_stage_attempt ("
                + "run_id TEXT NOT NULL, stage TEXT NOT NULL, attempt_number INTEGER NOT NULL, "
                + "idempotency_key TEXT NOT NULL, status TEXT NOT NULL, started_at INTEGER NOT NULL, "
                + "finished_at INTEGER, failure_reason TEXT, "
                + "PRIMARY KEY(run_id, stage, attempt_number))");
        jdbc.execute("CREATE TABLE harness_capability_snapshot ("
                + "run_id TEXT NOT NULL, stage TEXT NOT NULL, attempt_number INTEGER NOT NULL, "
                + "runtime TEXT NOT NULL, environment TEXT NOT NULL, policy_version TEXT NOT NULL, "
                + "prompt_pack_id TEXT NOT NULL, prompt_pack_version TEXT NOT NULL, "
                + "prompt_pack_hash TEXT NOT NULL, prompt_resource_hashes_json TEXT NOT NULL, "
                + "selected_skills_json TEXT NOT NULL, rejected_skills_json TEXT NOT NULL, "
                + "capability_decisions_json TEXT NOT NULL, prompt_parts_json TEXT NOT NULL, "
                + "final_prompt TEXT NOT NULL, prompt_hash TEXT NOT NULL, snapshot_hash TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, PRIMARY KEY(run_id, stage, attempt_number))");
    }

    private void insertLegacySnapshot(JdbcTemplate jdbc, CapabilitySnapshot snapshot) {
        jdbc.update("INSERT INTO harness_capability_snapshot VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                snapshot.getRunId(), snapshot.getStage().name(), snapshot.getAttemptNumber(),
                snapshot.getRuntime().name(), snapshot.getEnvironment(), snapshot.getPolicyVersion(),
                snapshot.getPromptPackId(), snapshot.getPromptPackVersion(), snapshot.getPromptPackHash(),
                CapabilitySnapshotJdbcCodec.json(snapshot.getPromptResourceHashes()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getSelectedSkills()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getRejectedSkills()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getCapabilityDecisions()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getPromptParts()), snapshot.getFinalPrompt(),
                snapshot.getPromptHash(), snapshot.getSnapshotHash(),
                snapshot.getCreatedAt().toEpochMilli());
    }

    private CapabilitySnapshot legacySnapshot() {
        PromptPack pack = new FileSystemPromptPackCatalog(
                Paths.get("src/main/resources/harness/prompt-packs"))
                .resolve(HarnessStage.ANALYSIS);
        String prompt = "legacy M2 prompt";
        HarnessPromptAssembly assembly = new HarnessPromptAssembly(Collections.singletonList(
                HarnessPromptPart.from(PromptPartType.CURRENT_INPUT, "input", prompt)),
                prompt, HarnessHashing.sha256(prompt));
        SkillSelection skills = new SkillSelection(Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        return CapabilitySnapshot.create("legacy-run", HarnessStage.ANALYSIS, 1,
                AgentRuntime.CODEX, "test", "harness-capability-policy@1.0.0",
                pack, skills, assembly, Instant.parse("2026-07-23T10:00:00Z"));
    }

    private List<String> columnNames(JdbcTemplate jdbc, String table) {
        return jdbc.query("PRAGMA table_info(" + table + ")",
                (resultSet, rowNumber) -> resultSet.getString("name"));
    }

    private List<String> tableNames(JdbcTemplate jdbc) {
        return jdbc.query("SELECT name FROM sqlite_master WHERE type='table'",
                (resultSet, rowNumber) -> resultSet.getString("name"));
    }
}
