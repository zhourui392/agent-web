package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 活动 Run Capability Binding Hash 的真实 SQLite 查询测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteActiveRunCapabilityBindingQueryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private Workbench workbench;
    private WorkbenchRunSnapshot snapshot;
    private SqliteActiveRunCapabilityBindingQuery query;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("active-capability.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "active-capability-workspace");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-active-capability");
        workbench.bindConversation(
                WorkbenchPhase.REVIEW_REFACTOR, "review-conversation",
                WorkbenchPersistenceFixtures.OWNER,
                WorkbenchPersistenceFixtures.NOW.plusSeconds(8));
        SqliteWorkbenchRepository workbenchRepository =
                new SqliteWorkbenchRepository(jdbc);
        workbenchRepository.add(workbench);
        ReviewOpinion opinion =
                WorkbenchPersistenceFixtures.reviewOpinion(workbench);
        ReviewModifyConfirmation confirmation =
                WorkbenchPersistenceFixtures.reviewConfirmation(workbench);
        new SqliteReviewOpinionRepository(jdbc).add(opinion);
        new SqliteReviewModifyConfirmationRepository(jdbc).add(confirmation);
        snapshot = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation,
                "active-review-run");
        new SqliteWorkbenchRunSnapshotRepository(jdbc).add(snapshot);
        workbench.prepareReviewRefactorRun(
                snapshot.getRunId(), RunMode.MODIFY_WORKSPACE,
                confirmation, WorkbenchPersistenceFixtures.OWNER,
                WorkbenchPersistenceFixtures.NOW.plusSeconds(9));
        workbenchRepository.update(workbench);
        query = new SqliteActiveRunCapabilityBindingQuery(jdbc);
    }

    @Test
    void shouldReturnOnlyExactActiveRunPhaseBindingHash() {
        Optional<String> active = query.findActiveBindingHash(
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR);

        assertEquals(snapshot.getCapabilityBinding().getBindingHash(),
                active.orElseThrow(AssertionError::new));
        assertFalse(query.findActiveBindingHash(
                workbench.getId(), WorkbenchPhase.IMPLEMENT_TEST).isPresent());
        assertFalse(query.findActiveBindingHash(
                WorkbenchId.of("missing-workbench"),
                WorkbenchPhase.REVIEW_REFACTOR).isPresent());
    }

    @Test
    void corruptBindingOrStoredHashShouldFailClosed() {
        jdbc.update("UPDATE workbench_run_snapshot "
                + "SET capability_bindings_json='{}' WHERE run_id=?",
                snapshot.getRunId());
        assertThrows(IllegalStateException.class,
                () -> query.findActiveBindingHash(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR));

        jdbc.update("UPDATE workbench_run_snapshot "
                        + "SET capability_bindings_json=?, "
                        + "capability_snapshot_hash=? WHERE run_id=?",
                new WorkbenchJsonCodec().writeCapabilityBinding(
                        snapshot.getCapabilityBinding()),
                WorkbenchPersistenceFixtures.HASH_F,
                snapshot.getRunId());
        assertThrows(IllegalStateException.class,
                () -> query.findActiveBindingHash(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR));
    }
}
