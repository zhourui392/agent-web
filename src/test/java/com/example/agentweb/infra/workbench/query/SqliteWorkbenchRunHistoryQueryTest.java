package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.run.WorkbenchRunDetailView;
import com.example.agentweb.app.workbench.run.WorkbenchRunListPage;
import com.example.agentweb.app.workbench.run.WorkbenchRunListRequest;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.infra.SqliteSessionRepo;
import com.example.agentweb.infra.chatrun.SqliteChatRunRepository;
import com.example.agentweb.infra.workbench.query.SqliteWorkbenchRunHistoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Dynamic Stage Workbench Run 历史读模型的真实 SQLite exact-binding 测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchRunHistoryQueryTest {

    private static final String WORKBENCH_IDENTIFIER =
            "workbench-run-history";
    private static final String STAGE_IDENTIFIER = "stage-design";
    private static final String SESSION_IDENTIFIER = "stage-session";

    @TempDir
    Path temporaryDirectory;

    private JdbcTemplate jdbcTemplate;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private Workbench workbench;
    private WorkbenchStageSnapshot stageSnapshot;
    private SqliteWorkbenchStageRunSnapshotRepository snapshotRepository;
    private SqliteChatRunRepository runRepository;
    private SqliteWorkbenchRunHistoryQuery query;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = WorkbenchPersistenceFixtures.initializedJdbc(
                temporaryDirectory.resolve("run-history.db"));
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbcTemplate, temporaryDirectory, "run-history-snapshot");
        stageSnapshot = stageSnapshot();
        workbench = Workbench.create(
                WorkbenchId.of(WORKBENCH_IDENTIFIER),
                WorkbenchPersistenceFixtures.OWNER,
                "Dynamic Workbench", "Implement Stage Run history",
                AgentType.CODEX, "local", workspace.scope(),
                workspace.snapshot().reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_IDENTIFIER, stageSnapshot)),
                WorkbenchPersistenceFixtures.NOW);
        workbench.bindStageConversation(
                STAGE_IDENTIFIER, SESSION_IDENTIFIER,
                WorkbenchPersistenceFixtures.OWNER,
                WorkbenchPersistenceFixtures.NOW.plusSeconds(1));
        new SqliteWorkbenchRepository(jdbcTemplate).add(workbench);
        persistStageSession();
        snapshotRepository =
                new SqliteWorkbenchStageRunSnapshotRepository(jdbcTemplate);
        runRepository = new SqliteChatRunRepository(jdbcTemplate);
        persistFailedRun(
                "history-run", "history-submission",
                WorkbenchPersistenceFixtures.HASH_A, "TEST_FAILURE", 12L);
        jdbcTemplate.update("INSERT INTO chat_run_event "
                        + "(run_id, seq, event_type, payload, payload_size, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                "history-run", 1L, "agent_chunk",
                "{\"content\":\"hello\"}", 19,
                WorkbenchPersistenceFixtures.NOW.plusSeconds(11)
                        .toEpochMilli());
        jdbcTemplate.update("INSERT INTO chat_run_event "
                        + "(run_id, seq, event_type, payload, payload_size, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                "history-run", 2L, "terminal",
                "{\"status\":\"FAILED\"}", 19,
                WorkbenchPersistenceFixtures.NOW.plusSeconds(12)
                        .toEpochMilli());
        query = new SqliteWorkbenchRunHistoryQuery(jdbcTemplate);
    }

    @Test
    void should_ListAndFindOnlyExactStageRunBinding() {
        // When
        WorkbenchRunListPage page = query.list(
                workbench.getId(),
                workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(
                        STAGE_IDENTIFIER, null, 20));

        // Then
        assertEquals(1, page.getItems().size());
        assertEquals("history-run", page.getItems().get(0).getRunId());
        assertEquals(STAGE_IDENTIFIER,
                page.getItems().get(0).getStageInstanceIdentifier());
        Optional<WorkbenchRunDetailView> detail = query.findDetail(
                workbench.getId(),
                workbench.getRepositoryScope().getScopeHash(),
                "history-run");
        assertEquals(SESSION_IDENTIFIER,
                detail.orElseThrow(AssertionError::new).getSessionId());
        assertEquals(STAGE_IDENTIFIER,
                detail.get().getStageInstanceIdentifier());
        assertEquals("FAILED", detail.get().getStatus().name());
        assertEquals(1L, detail.get().getEarliestRetainedSeq());
        assertEquals(2L, detail.get().getLastEventSeq());

        jdbcTemplate.update(
                "UPDATE chat_run SET origin_reference=? WHERE id=?",
                WORKBENCH_IDENTIFIER + ":another-stage", "history-run");
        assertFalse(query.findDetail(
                workbench.getId(),
                workbench.getRepositoryScope().getScopeHash(),
                "history-run").isPresent());
        assertEquals(0, query.list(
                workbench.getId(),
                workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(null, null, 20))
                .getItems().size());
    }

    @Test
    void should_ProduceStableDescendingPages_When_CursorIsUsed() {
        // Given
        persistFailedRun(
                "history-run-2", "history-submission-2",
                WorkbenchPersistenceFixtures.HASH_E,
                "SECOND_FAILURE", 13L);

        // When
        WorkbenchRunListPage first = query.list(
                workbench.getId(),
                workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(null, null, 1));

        // Then
        assertEquals(1, first.getItems().size());
        assertEquals("history-run-2", first.getItems().get(0).getRunId());
        assertNotNull(first.getNextCursor());

        WorkbenchRunListPage second = query.list(
                workbench.getId(),
                workbench.getRepositoryScope().getScopeHash(),
                new WorkbenchRunListRequest(
                        null, first.getNextCursor(), 1));

        assertEquals(1, second.getItems().size());
        assertEquals("history-run", second.getItems().get(0).getRunId());
        assertEquals(null, second.getNextCursor());
    }

    private void persistStageSession() {
        SqliteSessionRepo sessionRepository = new SqliteSessionRepo(
                jdbcTemplate,
                new CurrentUserProvider(() -> Optional.empty()));
        ChatSession session = ChatSession.createWorkbenchStage(
                SESSION_IDENTIFIER, AgentType.CODEX,
                workbench.getRepositoryScope().primaryRepository()
                        .getRepositoryRoot(),
                WORKBENCH_IDENTIFIER + ":" + STAGE_IDENTIFIER,
                WorkbenchPersistenceFixtures.OWNER.getOwnerId(),
                WorkbenchPersistenceFixtures.OWNER.getOwnerName(),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(1));
        session.setEnv("local");
        sessionRepository.addSession(session);
    }

    private void persistFailedRun(
            String runIdentifier, String submissionIdentifier,
            String requestHash, String failureCode,
            long finishedAtSeconds) {
        snapshotRepository.add(runSnapshot(
                runIdentifier, submissionIdentifier, requestHash));
        ChatRun run = ChatRun.submit(
                ChatRunId.of(runIdentifier), SESSION_IDENTIFIER, 1L,
                submissionIdentifier, false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        WORKBENCH_IDENTIFIER + ":" + STAGE_IDENTIFIER,
                        runIdentifier),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(10));
        run.allocateEventSequence(
                2, WorkbenchPersistenceFixtures.NOW.plusSeconds(11));
        run.fail(failureCode, "safe failure", Integer.valueOf(1),
                WorkbenchPersistenceFixtures.NOW
                        .plusSeconds(finishedAtSeconds));
        runRepository.add(run);
    }

    private WorkbenchStageRunSnapshot runSnapshot(
            String runIdentifier, String submissionIdentifier,
            String requestHash) {
        return WorkbenchStageRunSnapshot.create(
                runIdentifier, workbench.getId(), STAGE_IDENTIFIER,
                stageSnapshot, submissionIdentifier, requestHash,
                RunMode.DISCUSS_READ_ONLY, workspace.scope(),
                workspace.snapshot().reference(),
                WorkbenchPersistenceFixtures.capabilityBinding(), null,
                0L, WorkbenchPersistenceFixtures.HASH_B,
                Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner",
                        WorkbenchPersistenceFixtures.HASH_D, 32)),
                WorkbenchPersistenceFixtures.HASH_E,
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42.0",
                        workspace.scope().getScopeHash(), "agent-web",
                        1800L, 8_388_608L),
                Collections.emptyList(), Collections.emptyList(),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(10));
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "形成方案", "保持边界清晰",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator,
                WorkbenchPersistenceFixtures.NOW.minusSeconds(2));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator,
                        WorkbenchPersistenceFixtures.NOW.minusSeconds(1)));
    }
}
