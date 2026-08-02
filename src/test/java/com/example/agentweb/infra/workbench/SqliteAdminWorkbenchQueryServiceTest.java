package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.admin.AdminWorkbenchDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListPage;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListRequest;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListPage;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListRequest;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.infra.chatrun.SqliteChatRunRepository;
import com.example.agentweb.infra.workbench.admin.SqliteAdminWorkbenchQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立 Admin Workbench/Run 安全投影的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteAdminWorkbenchQueryServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteAdminWorkbenchQueryService query;
    private Workbench workbench;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("admin-workbench-query.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "admin-query-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "admin-query-workbench");
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
        WorkbenchRunSnapshot snapshot =
                WorkbenchPersistenceFixtures.reviewRunSnapshot(
                        workbench, workspace.snapshot(), confirmation,
                        "admin-query-run", "admin-query-submission",
                        WorkbenchPersistenceFixtures.HASH_E);
        new SqliteWorkbenchRunSnapshotRepository(jdbc).add(snapshot);
        ChatRun run = ChatRun.submit(
                ChatRunId.of("admin-query-run"),
                "review-session", 1L, "admin-query-submission",
                false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "admin-query-workbench:REVIEW_REFACTOR",
                        "admin-query-run"),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(2));
        run.start(WorkbenchPersistenceFixtures.NOW.plusSeconds(3));
        new SqliteChatRunRepository(jdbc).add(run);
        jdbc.update("INSERT INTO chat_run_runtime_handle "
                        + "(run_id, execution_id, handle_id, bound_at) "
                        + "VALUES (?,?,?,?)",
                "admin-query-run", "admin-query-run", "runtime-handle-1",
                WorkbenchPersistenceFixtures.NOW.plusSeconds(4).toEpochMilli());
        query = new SqliteAdminWorkbenchQueryService(jdbc);
    }

    @Test
    void shouldListAndViewWorkbenchAcrossOwnersWithoutPhysicalPathsOrGoalBody() {
        jdbc.update("UPDATE workbench SET owner_id=?, owner_name=? WHERE id=?",
                "owner-from-another-account", "Other Owner",
                "admin-query-workbench");
        AdminWorkbenchListPage page = query.list(
                new AdminWorkbenchListRequest(null, null, 20));

        assertEquals(1, page.getItems().size());
        assertEquals("admin-query-workbench",
                page.getItems().get(0).getWorkbenchId());
        assertEquals("owner-from-another-account",
                page.getItems().get(0).getOwnerId());

        Optional<AdminWorkbenchDetailView> found =
                query.findDetail("admin-query-workbench");
        assertTrue(found.isPresent());
        AdminWorkbenchDetailView detail = found.get();
        assertEquals(4, detail.getPhases().size());
        assertEquals(workbench.getRepositoryScope().getScopeHash(),
                detail.getRepositoryScopeHash());
        assertEquals(workbench.getRepositoryScope().getRepositories().size(),
                detail.getRepositories().size());
        assertFalse(fieldNames(AdminWorkbenchDetailView.class)
                .contains("workspaceRoot"));
        assertFalse(fieldNames(AdminWorkbenchDetailView.class)
                .contains("originalGoal"));
        assertFalse(fieldNames(AdminWorkbenchDetailView.RepositoryView.class)
                .contains("repositoryRoot"));
        assertFalse(fieldNames(AdminWorkbenchDetailView.RepositoryView.class)
                .contains("rootFingerprint"));
    }

    @Test
    void shouldListAndViewOnlyExactRunBindingWithoutSessionOrRawOutput() {
        AdminWorkbenchRunListPage page = query.listRuns(
                "admin-query-workbench",
                new AdminWorkbenchRunListRequest(null, null, 20));

        assertEquals(1, page.getItems().size());
        assertEquals("admin-query-run",
                page.getItems().get(0).getRunId());
        Optional<AdminWorkbenchRunDetailView> found = query.findRunDetail(
                "admin-query-workbench", "admin-query-run");
        assertTrue(found.isPresent());
        assertEquals(WorkbenchPhase.REVIEW_REFACTOR,
                found.get().getPhase());
        assertTrue(found.get().isRuntimeHandlePresent());
        Set<String> fields = fieldNames(AdminWorkbenchRunDetailView.class);
        assertFalse(fields.contains("sessionId"));
        assertFalse(fields.contains("errorMessage"));
        assertFalse(fields.contains("prompt"));
        assertFalse(fields.contains("toolOutput"));

        jdbc.update("UPDATE chat_run SET origin_reference=? WHERE id=?",
                "another-workbench:REVIEW_REFACTOR", "admin-query-run");

        assertTrue(query.listRuns(
                "admin-query-workbench",
                new AdminWorkbenchRunListRequest(null, null, 20))
                .getItems().isEmpty());
        assertFalse(query.findRunDetail(
                "admin-query-workbench", "admin-query-run").isPresent());

        jdbc.update("UPDATE chat_run SET origin_reference=?, "
                        + "execution_context_id=? WHERE id=?",
                "admin-query-workbench:REVIEW_REFACTOR", "another-run",
                "admin-query-run");

        assertTrue(query.listRuns(
                "admin-query-workbench",
                new AdminWorkbenchRunListRequest(null, null, 20))
                .getItems().isEmpty());
        assertFalse(query.findRunDetail(
                "admin-query-workbench", "admin-query-run").isPresent());
    }

    private static Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getName).collect(Collectors.toSet());
    }
}
