package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessRunView;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.infra.SqliteInitializer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness CQRS 详情和时间线投影测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SqliteHarnessRunQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void detail_should_project_stages_attempts_artifacts_gates_approvals_and_events() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("query.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
        HarnessRun run = runWaitingApproval();
        new SqliteHarnessRunRepository(jdbc).add(run);

        SqliteHarnessRunQueryService queryService = new SqliteHarnessRunQueryService(jdbc);
        HarnessRunView view = queryService.findById("run-1").orElseThrow(AssertionError::new);

        assertEquals("run-1", view.getRunId());
        assertEquals("WAITING_APPROVAL", view.getStatus());
        assertEquals(4, view.getStages().size());
        assertEquals("WAITING_APPROVAL", view.getStages().get(0).getStatus());
        assertEquals(1, view.getStages().get(0).getAttempts().size());
        assertEquals(4, view.getArtifacts().size());
        assertEquals(5, view.getGateResults().size());
        assertTrue(view.getApprovals().isEmpty());
        assertTrue(view.getEvents().stream()
                .anyMatch(event -> "APPROVAL_REQUESTED".equals(event.getEventType())));
        assertEquals(run.currentArtifactBaselineHash(HarnessStage.ANALYSIS),
                view.getStages().get(0).getArtifactBaselineHash());
        assertFalse(queryService.findById("missing").isPresent());
    }

    private HarnessRun runWaitingApproval() {
        HarnessRun run = HarnessRun.create("run-1", "M1", tempDir.toString(),
                "CODEX", "local", "harness@1.0.0", "admin", "create-key",
                StageContract.mvpDefaults(), NOW);
        HarnessStage stage = HarnessStage.ANALYSIS;
        run.startStage(stage, "start", NOW.plusSeconds(1));
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
        return run;
    }
}
