package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.domain.workbench.ProductionWriteTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Archive 只改变生命周期状态，所有审计和阶段事实必须保留。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchArchiveRetentionTest {

    @TempDir
    Path tempDir;

    @Test
    void archiveShouldRetainAllWorkbenchOwnedAndReferencedFacts() throws Exception {
        JdbcTemplate jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("archive-retention.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "archive-creation-snapshot");
        Workbench workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-archive-all");
        SqliteWorkbenchRepository workbenchRepository =
                new SqliteWorkbenchRepository(jdbc);
        workbenchRepository.add(workbench);

        PhaseHandoff handoff = PhaseHandoff.create(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "需求结论", Collections.singletonList(
                        Decision.confirmed("SQLite", "MVP")),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), workbench.getRepositoryScope(),
                OWNER, NOW.plusSeconds(2));
        new SqlitePhaseHandoffRepository(jdbc).add(handoff);
        new SqliteHandoffReceptionRepository(jdbc).save(HandoffReception.accept(
                workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, handoff.getVersion(),
                handoff.getContentHash(), OWNER, NOW.plusSeconds(3)));
        new SqlitePhaseCapabilityConfigurationRepository(jdbc).save(
                WorkbenchPersistenceFixtures.capabilityConfiguration(workbench));
        ReviewOpinion opinion = WorkbenchPersistenceFixtures.reviewOpinion(workbench);
        ReviewModifyConfirmation confirmation =
                WorkbenchPersistenceFixtures.reviewConfirmation(workbench);
        new SqliteReviewOpinionRepository(jdbc).add(opinion);
        new SqliteReviewModifyConfirmationRepository(jdbc).add(confirmation);
        new SqliteWorkbenchRunSnapshotRepository(jdbc).add(
                WorkbenchPersistenceFixtures.reviewRunSnapshot(
                        workbench, workspace.snapshot(), confirmation, "archive-run"));
        HighImpactOperation operation = HighImpactOperationPolicy
                .withAuthorizationTtl(Duration.ofMinutes(10))
                .propose(workbench, "archive-operation",
                        WorkbenchRunReference.of(
                                "archive-run", workbench.getId(),
                                WorkbenchPhase.REVIEW_REFACTOR, "Review 完成"),
                        ProductionWriteTarget.describe(
                                "production", "database/orders",
                                WorkbenchPersistenceFixtures.HASH_A),
                        "生产写入提案", OWNER, NOW.plusSeconds(11));
        new SqliteHighImpactOperationRepository(jdbc).add(operation);

        workbench.archive(OWNER, NOW.plusSeconds(12));
        workbenchRepository.update(workbench);

        assertEquals(WorkbenchStatus.ARCHIVED,
                workbenchRepository.findById(workbench.getId())
                        .orElseThrow(AssertionError::new).getStatus());
        assertEquals(4, count(jdbc, "workbench_phase"));
        assertEquals(2, count(jdbc, "workbench_repository_scope"));
        assertEquals(1, count(jdbc, "workbench_phase_handoff"));
        assertEquals(1, count(jdbc, "workbench_handoff_reception"));
        assertEquals(1, count(jdbc, "workbench_phase_capability_config"));
        assertEquals(1, count(jdbc, "workbench_review_opinion"));
        assertEquals(1, count(jdbc, "workbench_review_modify_confirmation"));
        assertEquals(1, count(jdbc, "workbench_run_snapshot"));
        assertEquals(1, count(jdbc, "workbench_high_impact_operation"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_snapshot WHERE snapshot_id=?",
                Integer.class, "archive-creation-snapshot"));
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
