package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.CapabilitySnapshotView;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityGrant;
import com.example.agentweb.domain.harness.CapabilitySelectionRequest;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.HarnessPromptAssembler;
import com.example.agentweb.domain.harness.HarnessPromptAssembly;
import com.example.agentweb.domain.harness.HarnessPromptAssemblyRequest;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.SkillSelection;
import com.example.agentweb.domain.harness.SkillSelectionPolicy;
import com.example.agentweb.domain.harness.StageCapabilityPolicy;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capability Snapshot 真实 SQLite 不可变持久化与 CQRS 预览测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SqliteCapabilitySnapshotRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteCapabilitySnapshotRepository repository;
    private FileSystemPromptPackCatalog promptCatalog;
    private FileSystemSkillCatalog skillCatalog;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("snapshot.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        repository = new SqliteCapabilitySnapshotRepository(jdbc);
        promptCatalog = new FileSystemPromptPackCatalog(
                java.nio.file.Paths.get("src/main/resources/harness/prompt-packs"));
        skillCatalog = new FileSystemSkillCatalog(
                java.nio.file.Paths.get("src/main/resources/harness/skills"),
                com.example.agentweb.domain.harness.SkillTrustSource.PLATFORM);

        HarnessRun run = HarnessRun.create("run-1", "M2", tempDir.toString(), "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", StageContract.mvpDefaults(),
                Instant.parse("2026-07-23T10:00:00Z"));
        run.startStage(HarnessStage.ANALYSIS, "start-1", Instant.parse("2026-07-23T10:01:00Z"));
        new SqliteHarnessRunRepository(jdbc).add(run);
    }

    @Test
    void shouldRestoreCompleteSnapshotAfterRepositoryRestart() {
        CapabilitySnapshot snapshot = snapshot("current input", Instant.parse("2026-07-23T10:02:00Z"));

        repository.saveIfAbsent(snapshot);
        CapabilitySnapshot restored = new SqliteCapabilitySnapshotRepository(jdbc)
                .find("run-1", HarnessStage.ANALYSIS, 1).orElseThrow(AssertionError::new);

        assertEquals(snapshot.getSnapshotHash(), restored.getSnapshotHash());
        assertEquals(snapshot.getPromptHash(), restored.getPromptHash());
        assertEquals(snapshot.getFinalPrompt(), restored.getFinalPrompt());
        assertEquals("domain-modeling-audit", restored.getSelectedSkills().get(0).getId());
        assertFalse(restored.getPromptParts().isEmpty());
    }

    @Test
    void shouldKeepFirstSnapshotWhenSameAttemptIsResolvedAgain() {
        CapabilitySnapshot first = snapshot("first input", Instant.parse("2026-07-23T10:02:00Z"));
        CapabilitySnapshot changed = snapshot("changed input", Instant.parse("2026-07-23T10:03:00Z"));

        CapabilitySnapshot saved = repository.saveIfAbsent(first);
        CapabilitySnapshot existing = repository.saveIfAbsent(changed);

        assertEquals(first.getSnapshotHash(), saved.getSnapshotHash());
        assertEquals(first.getSnapshotHash(), existing.getSnapshotHash());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM harness_capability_snapshot",
                Integer.class).intValue());
    }

    @Test
    void queryServiceShouldProjectSnapshotWithoutReturningPartialAggregate() {
        CapabilitySnapshot snapshot = snapshot("current input", Instant.parse("2026-07-23T10:02:00Z"));
        repository.saveIfAbsent(snapshot);
        SqliteCapabilitySnapshotQueryService queryService = new SqliteCapabilitySnapshotQueryService(jdbc);

        CapabilitySnapshotView view = queryService.find("run-1", HarnessStage.ANALYSIS, 1)
                .orElseThrow(AssertionError::new);

        assertEquals(snapshot.getSnapshotHash(), view.getSnapshotHash());
        assertEquals("STAGE_DEFAULT", view.getSelectedSkills().get(0).getReason().name());
        assertTrue(queryService.find("missing", HarnessStage.ANALYSIS, 1).isEmpty());
    }

    private CapabilitySnapshot snapshot(String currentInput, Instant createdAt) {
        PromptPack pack = promptCatalog.resolve(HarnessStage.ANALYSIS);
        SkillSelection selection = new SkillSelectionPolicy().select(
                new CapabilitySelectionRequest(HarnessStage.ANALYSIS, AgentRuntime.CODEX,
                        StageCapabilityPolicy.defaultsFor(HarnessStage.ANALYSIS),
                        Collections.<String>emptySet(), Collections.singleton("java"),
                        Collections.<String>emptySet(), CapabilityGrant.none()),
                skillCatalog.discover());
        HarnessPromptAssembly assembly = new HarnessPromptAssembler().assemble(
                new HarnessPromptAssemblyRequest("platform safety", "test guardrail", "stage contract",
                        pack, selection, "approved upstream", currentInput));
        return CapabilitySnapshot.create("run-1", HarnessStage.ANALYSIS, 1, AgentRuntime.CODEX,
                "test", "harness-capability-policy@1.0.0", pack, selection, assembly, createdAt);
    }
}
