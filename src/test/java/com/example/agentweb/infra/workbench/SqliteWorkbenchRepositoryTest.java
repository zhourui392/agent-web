package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseState;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench 聚合、Repository Scope、固定 Phase 与会话代际的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteWorkbenchRepository repository;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("workbench.db"));
        repository = new SqliteWorkbenchRepository(jdbc);
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbc, tempDir, "creation-snapshot");
    }

    @Test
    void addAndFindShouldRoundTripScopeFourPhasesConversationGenerationsAndWriteLease() {
        Workbench source = activeWorkbench("workbench-round-trip");

        repository.add(source);

        Workbench restored = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertWorkbench(source, restored);
        assertEquals(4, restored.getPhases().size());
        WorkbenchPhaseState analysis = restored.phase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS);
        assertEquals(2, analysis.getConversationHistory().size());
        assertEquals(1, analysis.getConversationGeneration());
        assertEquals("analysis-v1-workbench-round-trip",
                analysis.currentConversation().getConversationId());
        assertNotNull(analysis.getConversationHistory().get(0).getRetiredAt());
        assertNotNull(restored.getActiveWriteRunReference());
        assertEquals("implement-run-workbench-round-trip",
                restored.getActiveWriteRunReference().getRunId());
        assertEquals(RunMode.MODIFY_WORKSPACE,
                restored.getActiveWriteRunReference().getRunMode());
    }

    @Test
    void updateShouldUseOptimisticVersionAndKeepWinnerChildrenAtomically() {
        Workbench source = activeWorkbench("workbench-lock");
        repository.add(source);
        Workbench winner = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        Workbench stale = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        winner.finishRun(WorkbenchPhase.IMPLEMENT_TEST,
                "implement-run-workbench-lock", NOW.plusSeconds(7));
        stale.finishRun(WorkbenchPhase.IMPLEMENT_TEST,
                "implement-run-workbench-lock", NOW.plusSeconds(7));

        repository.update(winner);

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class, () -> repository.update(stale));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, conflict.getCode());
        Workbench restored = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertNull(restored.getActiveWriteRunReference());
        assertNull(restored.phase(WorkbenchPhase.IMPLEMENT_TEST).getActiveRunReference());
        assertEquals(winner.getVersion(), restored.getVersion());
    }

    @Test
    void updateAndFindShouldRestoreActiveReviewModifyProofWithoutFabricatingOpinion() {
        Workbench initial = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-review-active");
        repository.add(initial);
        ReviewOpinion opinion = WorkbenchPersistenceFixtures.reviewOpinion(initial);
        ReviewModifyConfirmation confirmation =
                WorkbenchPersistenceFixtures.reviewConfirmation(initial);
        new SqliteReviewOpinionRepository(jdbc).add(opinion);
        new SqliteReviewModifyConfirmationRepository(jdbc).add(confirmation);
        Workbench bound = repository.findById(initial.getId())
                .orElseThrow(AssertionError::new);
        bound.bindConversation(
                WorkbenchPhase.REVIEW_REFACTOR, "review-v0", OWNER,
                NOW.plusSeconds(8));
        repository.update(bound);
        Workbench prepared = repository.findById(initial.getId())
                .orElseThrow(AssertionError::new);
        prepared.prepareReviewRefactorRun(
                "review-modify-run", RunMode.MODIFY_WORKSPACE,
                confirmation, OWNER, NOW.plusSeconds(9));

        repository.update(prepared);

        Workbench restored = repository.findById(initial.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(prepared.getActiveWriteRunReference(),
                restored.getActiveWriteRunReference());
        assertEquals("confirmation-1",
                restored.getActiveWriteRunReference().getReviewConfirmationId());
        assertEquals(2L, restored.getActiveWriteRunReference()
                .getReviewOpinionVersion().longValue());
        assertEquals(WorkbenchPersistenceFixtures.HASH_F,
                restored.getActiveWriteRunReference().getReviewOpinionHash());
    }

    @Test
    void schemaShouldEnforceForeignKeysAndOneModifyRunPerWorkbench() {
        Workbench source = activeWorkbench("workbench-constraints");
        repository.add(source);

        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO workbench_repository_scope "
                        + "(workbench_id, repository_key, relative_path, repository_root, "
                        + "root_fingerprint, primary_repository) VALUES (?,?,?,?,?,?)",
                "missing-workbench", "orphan", "orphan", "/workspace/orphan",
                WorkbenchPersistenceFixtures.HASH_A, 1));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE workbench_phase SET active_run_id=?, active_run_mode=?, "
                        + "active_run_prepared_at=? WHERE workbench_id=? AND phase=?",
                "second-write", RunMode.MODIFY_WORKSPACE.name(), NOW.toEpochMilli(),
                source.getId().getValue(), WorkbenchPhase.SOLUTION_DESIGN.name()));
    }

    @Test
    void findShouldFailFastForMissingPhaseScopeHashAndLeaseMismatch() {
        Workbench missingPhase = activeWorkbench("workbench-missing-phase");
        repository.add(missingPhase);
        jdbc.update("DELETE FROM workbench_phase WHERE workbench_id=? AND phase=?",
                missingPhase.getId().getValue(), WorkbenchPhase.SOLUTION_DESIGN.name());
        assertThrows(IllegalStateException.class,
                () -> repository.findById(missingPhase.getId()));

        Workbench badScope = activeWorkbench("workbench-bad-scope");
        repository.add(badScope);
        jdbc.update("UPDATE workbench SET repository_scope_hash=? WHERE id=?",
                WorkbenchPersistenceFixtures.HASH_F, badScope.getId().getValue());
        assertThrows(IllegalStateException.class,
                () -> repository.findById(badScope.getId()));

        Workbench badLease = activeWorkbench("workbench-bad-lease");
        repository.add(badLease);
        jdbc.update("UPDATE workbench SET active_write_run_id=NULL WHERE id=?",
                badLease.getId().getValue());
        assertThrows(IllegalStateException.class,
                () -> repository.findById(badLease.getId()));
    }

    @Test
    void findShouldFailFastWhenParentWriteLeaseMatchesNoActivePhaseRun() {
        Workbench source = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-ghost-write-lease");
        repository.add(source);
        jdbc.update("UPDATE workbench SET active_write_run_id=? WHERE id=?",
                "ghost-run", source.getId().getValue());

        assertThrows(IllegalStateException.class,
                () -> repository.findById(source.getId()));
    }

    @Test
    void archivedWorkbenchShouldRetainScopePhasesAndConversationHistory() {
        Workbench source = activeWorkbench("workbench-archive");
        repository.add(source);
        Workbench loaded = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        loaded.finishRun(
                WorkbenchPhase.IMPLEMENT_TEST,
                "implement-run-workbench-archive", NOW.plusSeconds(7));
        repository.update(loaded);
        loaded.archive(OWNER, NOW.plusSeconds(8));
        repository.update(loaded);

        Workbench archived = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(WorkbenchStatus.ARCHIVED, archived.getStatus());
        assertEquals(4, archived.getPhases().size());
        assertEquals(2, archived.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .getConversationHistory().size());
        assertEquals(2, archived.getRepositoryScope().repositoryCount());
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_phase_conversation WHERE workbench_id=?",
                Integer.class, source.getId().getValue()) >= 3);
    }

    @Test
    void duplicateAddMustNotOverwriteAndMissingIdShouldBeEmpty() {
        Workbench source = activeWorkbench("workbench-duplicate");
        repository.add(source);

        assertThrows(IllegalStateException.class, () -> repository.add(source));
        assertFalse(repository.findById(WorkbenchId.of("missing")).isPresent());
        assertEquals(source.getVersion(), repository.findById(source.getId())
                .orElseThrow(AssertionError::new).getVersion());
    }

    private Workbench activeWorkbench(String id) {
        Workbench workbench = WorkbenchPersistenceFixtures.newWorkbench(workspace, id);
        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "analysis-v0-" + id, OWNER,
                NOW.plusSeconds(1));
        workbench.prepareRun(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "analysis-run-" + id,
                RunMode.DISCUSS_READ_ONLY, OWNER, NOW.plusSeconds(2));
        workbench.finishRun(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "analysis-run-" + id,
                NOW.plusSeconds(3));
        workbench.restartConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "analysis-v1-" + id, OWNER,
                NOW.plusSeconds(4));
        workbench.bindConversation(
                WorkbenchPhase.IMPLEMENT_TEST, "implement-v0-" + id, OWNER,
                NOW.plusSeconds(5));
        workbench.prepareRun(
                WorkbenchPhase.IMPLEMENT_TEST, "implement-run-" + id,
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(6));
        return workbench;
    }

    private void assertWorkbench(Workbench expected, Workbench actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getOwner(), actual.getOwner());
        assertEquals(expected.getTitle(), actual.getTitle());
        assertEquals(expected.getOriginalGoal(), actual.getOriginalGoal());
        assertEquals(expected.getAgentType(), actual.getAgentType());
        assertEquals(expected.getEnvironment(), actual.getEnvironment());
        assertEquals(expected.getRepositoryScope(), actual.getRepositoryScope());
        assertEquals(expected.getCreationSnapshotReference(),
                actual.getCreationSnapshotReference());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.getVersion(), actual.getVersion());
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            WorkbenchPhaseState expectedPhase = expected.phase(phase);
            WorkbenchPhaseState actualPhase = actual.phase(phase);
            assertEquals(expectedPhase.getStatus(), actualPhase.getStatus());
            assertEquals(expectedPhase.getConversationGeneration(),
                    actualPhase.getConversationGeneration());
            assertEquals(expectedPhase.getConversationHistory(),
                    actualPhase.getConversationHistory());
            assertEquals(expectedPhase.getActiveRunReference(),
                    actualPhase.getActiveRunReference());
            assertEquals(expectedPhase.getLastActivityAt(), actualPhase.getLastActivityAt());
            assertEquals(expectedPhase.getCompletedAt(), actualPhase.getCompletedAt());
        }
        assertEquals(WorkbenchPhaseStatus.IN_PROGRESS,
                actual.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS).getStatus());
    }
}
