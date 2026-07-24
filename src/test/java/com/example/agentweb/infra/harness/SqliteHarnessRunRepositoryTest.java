package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.Approval;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.DuplicateHarnessRunException;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunStatus;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.StageStatus;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 聚合真实 SQLite 持久化、重启恢复和幂等迁移测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SqliteHarnessRunRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @TempDir
    Path tempDir;

    private String jdbcUrl;
    private JdbcTemplate jdbc;
    private SqliteHarnessRunRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("harness.db").toAbsolutePath();
        jdbc = jdbc(jdbcUrl);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        repository = new SqliteHarnessRunRepository(jdbc);
    }

    @Test
    void add_and_restart_should_restore_full_aggregate_timeline() {
        HarnessRun run = newRun("run-1", "create-1");
        passAnalysis(run);
        repository.add(run);

        SqliteHarnessRunRepository restarted = new SqliteHarnessRunRepository(jdbc(jdbcUrl));
        HarnessRun loaded = restarted.findById("run-1").orElseThrow(AssertionError::new);

        assertEquals(HarnessRunStatus.ACTIVE, loaded.getStatus());
        assertEquals(StageStatus.PASSED, loaded.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(1, loaded.stage(HarnessStage.ANALYSIS).getAttempts().size());
        assertEquals(4, loaded.getArtifacts().size());
        assertEquals(5, loaded.getGateResults().size());
        assertEquals(1, loaded.getApprovals().size());
        assertTrue(loaded.getApprovals().get(0).isValid());
        assertTrue(loaded.getEvents().size() >= 13);
        assertEquals(0L, loaded.getVersion());
        assertTrue(restarted.findByCreatorAndIdempotencyKey("admin", "create-1").isPresent());
        assertFalse(restarted.findById("missing").isPresent());
    }

    @Test
    void update_should_use_optimistic_lock_and_keep_old_attempt_evidence() {
        HarnessRun original = newRun("run-1", "create-1");
        repository.add(original);
        HarnessRun copyA = repository.findById("run-1").orElseThrow(AssertionError::new);
        HarnessRun copyB = repository.findById("run-1").orElseThrow(AssertionError::new);
        copyA.startStage(HarnessStage.ANALYSIS, "start-a", NOW.plusSeconds(1));
        copyB.startStage(HarnessStage.ANALYSIS, "start-b", NOW.plusSeconds(1));

        repository.update(copyA);

        assertEquals(1L, copyA.getVersion());
        assertThrows(IllegalStateException.class, () -> repository.update(copyB));
        HarnessRun loaded = repository.findById("run-1").orElseThrow(AssertionError::new);
        assertEquals("start-a", loaded.stage(HarnessStage.ANALYSIS)
                .currentAttempt().getIdempotencyKey());
    }

    @Test
    void creator_and_idempotency_key_should_be_unique() {
        repository.add(newRun("run-1", "same-key"));

        assertThrows(DuplicateHarnessRunException.class,
                () -> repository.add(newRun("run-2", "same-key")));
    }

    @Test
    void cancelled_before_start_should_restore_without_synthetic_attempts() {
        HarnessRun run = newRun("run-cancelled", "cancel-key");
        run.cancel("admin", "cancel draft", NOW.plusSeconds(1));
        repository.add(run);

        HarnessRun loaded = repository.findById("run-cancelled").orElseThrow(AssertionError::new);

        assertEquals(HarnessRunStatus.CANCELLED, loaded.getStatus());
        assertTrue(loaded.getStages().stream().allMatch(stage -> stage.getAttempts().isEmpty()));
        assertTrue(loaded.getStages().stream()
                .allMatch(stage -> stage.getStatus() == StageStatus.CANCELLED));
    }

    @Test
    void update_and_restart_should_restore_waiting_input_question_and_answer() {
        HarnessRun run = newRun("run-question", "question-key");
        run.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));
        run.requestInput(HarnessStage.ANALYSIS, "question-1", "Which tenant?", true,
                "agent", NOW.plusSeconds(2));
        repository.add(run);

        HarnessRun waiting = new SqliteHarnessRunRepository(jdbc(jdbcUrl))
                .findById("run-question").orElseThrow(AssertionError::new);
        assertEquals(HarnessRunStatus.WAITING_INPUT, waiting.getStatus());
        assertEquals(1, waiting.getQuestions().size());
        assertFalse(waiting.getQuestions().get(0).isAnswered());

        waiting.answerQuestion("question-1", "tenant-a", "admin", NOW.plusSeconds(3));
        repository.update(waiting);
        HarnessRun answered = new SqliteHarnessRunRepository(jdbc(jdbcUrl))
                .findById("run-question").orElseThrow(AssertionError::new);

        assertEquals(HarnessRunStatus.ACTIVE, answered.getStatus());
        assertEquals("tenant-a", answered.getQuestions().get(0).getAnswer());
        assertEquals("admin", answered.getQuestions().get(0).getAnsweredBy());
        assertEquals(StageStatus.RUNNING, answered.stage(HarnessStage.ANALYSIS).getStatus());
    }

    @Test
    void add_and_restart_should_restore_creation_git_baseline() {
        WorkspaceBaseline baseline = WorkspaceBaseline.capture(tempDir.toString(), "feat/m4",
                "0123456789012345678901234567890123456789", false,
                String.join("", Collections.nCopies(64, "a")), NOW);
        HarnessRun run = HarnessRun.create("run-baseline", "M4", tempDir.toString(), "CODEX",
                "local", "harness@1.0.0", "admin", "baseline-key", baseline,
                StageContract.mvpDefaults(), NOW);

        repository.add(run);
        HarnessRun loaded = new SqliteHarnessRunRepository(jdbc(jdbcUrl))
                .findById("run-baseline").orElseThrow(AssertionError::new);

        assertEquals("feat/m4", loaded.getWorkspaceBaseline().getBranch());
        assertEquals("0123456789012345678901234567890123456789",
                loaded.getWorkspaceBaseline().getHead());
        assertFalse(loaded.getWorkspaceBaseline().isClean());
        assertEquals(String.join("", Collections.nCopies(64, "a")),
                loaded.getWorkspaceBaseline().getDiffHash());
    }

    private HarnessRun newRun(String runId, String idempotencyKey) {
        return HarnessRun.create(runId, "M1", tempDir.toString(), "CODEX", "local",
                "harness@1.0.0", "admin", idempotencyKey,
                StageContract.mvpDefaults(), NOW);
    }

    private void passAnalysis(HarnessRun run) {
        HarnessStage stage = HarnessStage.ANALYSIS;
        run.startStage(stage, "start-analysis", NOW.plusSeconds(1));
        int offset = 2;
        for (ArtifactType type : run.stage(stage).getContract().getRequiredOutputArtifacts()) {
            run.registerArtifact(stage, "artifact-" + type.name(), type,
                    ArtifactContent.from(type.name().getBytes(StandardCharsets.UTF_8)),
                    "text/markdown", ArtifactClassification.INTERNAL, "admin",
                    Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(offset++));
        }
        for (String gate : run.stage(stage).getContract().getDeterministicGates()) {
            run.recordGate(stage, "gate-" + gate, gate, true,
                    Collections.<String>emptyList(), null, NOW.plusSeconds(20));
        }
        run.submitForApproval(stage, NOW.plusSeconds(21));
        String baselineHash = run.currentArtifactBaselineHash(stage);
        run.approve(stage, "approval-analysis", baselineHash,
                "admin", "approved", NOW.plusSeconds(22));
        assertTrue(run.getApprovals().stream().map(Approval::getArtifactBaselineHash)
                .anyMatch(baselineHash::equals));
    }

    private JdbcTemplate jdbc(String url) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return new JdbcTemplate(dataSource);
    }
}
