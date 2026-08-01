package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.run.WorkbenchRunDetailView;
import com.example.agentweb.app.workbench.run.WorkbenchRunListPage;
import com.example.agentweb.app.workbench.run.WorkbenchRunListRequest;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.infra.chatrun.SqliteChatRunRepository;
import com.example.agentweb.infra.workbench.query.SqliteWorkbenchRunHistoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Workbench Run 历史读模型的真实 SQLite exact-binding 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchRunHistoryQueryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private Workbench workbench;
    private WorkbenchRunSnapshot snapshot;
    private SqliteWorkbenchRunHistoryQuery query;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("run-history.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "run-history-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-run-history");
        workbench.bindConversation(
                WorkbenchPhase.REVIEW_REFACTOR, "review-session",
                WorkbenchPersistenceFixtures.OWNER,
                WorkbenchPersistenceFixtures.NOW.plusSeconds(1));
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        ReviewOpinion opinion =
                WorkbenchPersistenceFixtures.reviewOpinion(workbench);
        ReviewModifyConfirmation confirmation =
                WorkbenchPersistenceFixtures.reviewConfirmation(workbench);
        new SqliteReviewOpinionRepository(jdbc).add(opinion);
        new SqliteReviewModifyConfirmationRepository(jdbc).add(confirmation);
        snapshot = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "history-run");
        new SqliteWorkbenchRunSnapshotRepository(jdbc).add(snapshot);
        ChatRun run = ChatRun.submit(
                ChatRunId.of("history-run"), "review-session", 1L,
                "history-submission", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-run-history:REVIEW_REFACTOR",
                        "history-run"),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(10));
        run.allocateEventSequence(
                2, WorkbenchPersistenceFixtures.NOW.plusSeconds(11));
        run.fail("TEST_FAILURE", "safe failure", Integer.valueOf(1),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(12));
        new SqliteChatRunRepository(jdbc).add(run);
        jdbc.update("INSERT INTO chat_run_event "
                        + "(run_id, seq, event_type, payload, payload_size, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                "history-run", 1L, "agent_chunk", "{\"content\":\"hello\"}",
                19, WorkbenchPersistenceFixtures.NOW.plusSeconds(11).toEpochMilli());
        jdbc.update("INSERT INTO chat_run_event "
                        + "(run_id, seq, event_type, payload, payload_size, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                "history-run", 2L, "terminal", "{\"status\":\"FAILED\"}",
                19, WorkbenchPersistenceFixtures.NOW.plusSeconds(12).toEpochMilli());
        query = new SqliteWorkbenchRunHistoryQuery(jdbc);
    }

    @Test
    void shouldListAndFindOnlyExactWorkbenchPhaseRunBinding() {
        WorkbenchRunListPage page = query.list(
                workbench.getId(), workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(
                        WorkbenchPhase.REVIEW_REFACTOR, null, 20));

        assertEquals(1, page.getItems().size());
        assertEquals("history-run", page.getItems().get(0).getRunId());
        assertEquals(WorkbenchPhase.REVIEW_REFACTOR,
                page.getItems().get(0).getPhase());
        Optional<WorkbenchRunDetailView> detail = query.findDetail(
                workbench.getId(), workbench.getRepositoryScope().getScopeHash(),
                "history-run");
        assertEquals("review-session",
                detail.orElseThrow(AssertionError::new).getSessionId());
        assertEquals("FAILED", detail.get().getStatus().name());
        assertEquals(1L, detail.get().getEarliestRetainedSeq());
        assertEquals(2L, detail.get().getLastEventSeq());

        jdbc.update("UPDATE chat_run SET origin_reference=? WHERE id=?",
                "another-workbench:REVIEW_REFACTOR", "history-run");
        assertFalse(query.findDetail(
                workbench.getId(), workbench.getRepositoryScope().getScopeHash(),
                "history-run").isPresent());
        assertEquals(0, query.list(
                workbench.getId(), workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(null, null, 20))
                .getItems().size());
    }

    @Test
    void cursorShouldProduceStableDescendingPages() {
        WorkbenchRunSnapshot secondSnapshot =
                WorkbenchPersistenceFixtures.reviewRunSnapshot(
                        workbench,
                        new com.example.agentweb.infra.workspace.SqliteWorkspaceSnapshotRepository(
                                jdbc).findById(snapshot.getWorkspaceSnapshotReference()
                                .getSnapshotId()).orElseThrow(AssertionError::new),
                        WorkbenchPersistenceFixtures.reviewConfirmation(workbench),
                        "history-run-2", "history-submission-2",
                        WorkbenchPersistenceFixtures.HASH_E);
        new SqliteWorkbenchRunSnapshotRepository(jdbc).add(secondSnapshot);
        ChatRun secondRun = ChatRun.submit(
                ChatRunId.of("history-run-2"), "review-session", 2L,
                "history-submission-2", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-run-history:REVIEW_REFACTOR",
                        "history-run-2"),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(10));
        secondRun.fail("SECOND_FAILURE", "safe failure", Integer.valueOf(2),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(13));
        new SqliteChatRunRepository(jdbc).add(secondRun);
        WorkbenchRunListPage first = query.list(
                workbench.getId(), workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(null, null, 1));

        assertEquals(1, first.getItems().size());
        assertEquals("history-run-2", first.getItems().get(0).getRunId());
        org.junit.jupiter.api.Assertions.assertNotNull(first.getNextCursor());

        WorkbenchRunListPage second = query.list(
                workbench.getId(), workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(
                        null, first.getNextCursor(), 1));

        assertEquals(1, second.getItems().size());
        assertEquals("history-run", second.getItems().get(0).getRunId());
        assertEquals(null, second.getNextCursor());
    }
}
