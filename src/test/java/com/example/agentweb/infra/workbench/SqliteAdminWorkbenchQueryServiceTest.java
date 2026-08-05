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
import com.example.agentweb.infra.chatrun.SqliteChatRunRepository;
import com.example.agentweb.infra.workbench.admin.SqliteAdminWorkbenchQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
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

    private static final String WORKBENCH_IDENTIFIER =
            "admin-query-workbench";
    private static final String STAGE_IDENTIFIER = "stage-review";

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteAdminWorkbenchQueryService query;
    private Workbench workbench;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private WorkbenchStageSnapshot stageSnapshot;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("admin-workbench-query.db"));
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbc, tempDir, "admin-query-snapshot");
        stageSnapshot = stageSnapshot();
        workbench = Workbench.create(
                WorkbenchId.of(WORKBENCH_IDENTIFIER),
                WorkbenchPersistenceFixtures.OWNER,
                "Dynamic Workbench", "Admin Stage projection",
                AgentType.CODEX, "local", workspace.scope(),
                workspace.snapshot().reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_IDENTIFIER, stageSnapshot)),
                WorkbenchPersistenceFixtures.NOW);
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        new SqliteWorkbenchStageRunSnapshotRepository(jdbc).add(
                runSnapshot());
        ChatRun run = ChatRun.submit(
                ChatRunId.of("admin-query-run"),
                "stage-session", 1L, "admin-query-submission",
                false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        WORKBENCH_IDENTIFIER + ":" + STAGE_IDENTIFIER,
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
        assertEquals(1, detail.getStages().size());
        assertEquals(STAGE_IDENTIFIER,
                detail.getStages().get(0).getStageInstanceIdentifier());
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
        assertEquals(STAGE_IDENTIFIER,
                found.get().getStageInstanceIdentifier());
        assertTrue(found.get().isRuntimeHandlePresent());
        Set<String> fields = fieldNames(AdminWorkbenchRunDetailView.class);
        assertFalse(fields.contains("sessionId"));
        assertFalse(fields.contains("errorMessage"));
        assertFalse(fields.contains("prompt"));
        assertFalse(fields.contains("toolOutput"));

        jdbc.update("UPDATE chat_run SET origin_reference=? WHERE id=?",
                "another-workbench:" + STAGE_IDENTIFIER,
                "admin-query-run");

        assertTrue(query.listRuns(
                "admin-query-workbench",
                new AdminWorkbenchRunListRequest(null, null, 20))
                .getItems().isEmpty());
        assertFalse(query.findRunDetail(
                "admin-query-workbench", "admin-query-run").isPresent());

        jdbc.update("UPDATE chat_run SET origin_reference=?, "
                        + "execution_context_id=? WHERE id=?",
                WORKBENCH_IDENTIFIER + ":" + STAGE_IDENTIFIER,
                "another-run",
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

    private WorkbenchStageRunSnapshot runSnapshot() {
        return WorkbenchStageRunSnapshot.create(
                "admin-query-run", workbench.getId(), STAGE_IDENTIFIER,
                stageSnapshot, "admin-query-submission",
                WorkbenchPersistenceFixtures.HASH_E,
                RunMode.DISCUSS_READ_ONLY, workspace.scope(),
                workspace.snapshot().reference(),
                WorkbenchPersistenceFixtures.capabilityBinding(), null,
                0L, WorkbenchPersistenceFixtures.HASH_B,
                Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner",
                        WorkbenchPersistenceFixtures.HASH_D, 32)),
                WorkbenchPersistenceFixtures.HASH_A,
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42.0", workspace.scope().getScopeHash(),
                        "agent-web", 1800L, 8_388_608L),
                Collections.emptyList(), Collections.emptyList(),
                WorkbenchPersistenceFixtures.NOW.plusSeconds(2));
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "review-stage",
                WorkbenchStageDraftContent.create(
                        10, "Review", "Review changes", "Keep output safe",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator,
                WorkbenchPersistenceFixtures.NOW.minusSeconds(2));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "review-stage", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator,
                        WorkbenchPersistenceFixtures.NOW.minusSeconds(1)));
    }
}
