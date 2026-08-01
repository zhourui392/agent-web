package com.example.agentweb.infra.workbench.query;

import com.example.agentweb.app.workbench.query.WorkbenchDetailView;
import com.example.agentweb.app.workbench.query.WorkbenchListCursor;
import com.example.agentweb.app.workbench.query.WorkbenchListItemView;
import com.example.agentweb.app.workbench.query.WorkbenchListPage;
import com.example.agentweb.app.workbench.query.WorkbenchListRequest;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench Owner 读模型的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchQueryServiceTest {

    private static final long BASE_TIME = 1_785_561_600_000L;
    private static final String HASH_A = repeat('a');
    private static final String HASH_B = repeat('b');
    private static final String HASH_C = repeat('c');
    private static final String HASH_D = repeat('d');
    private static final String HASH_E = repeat('e');

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteWorkbenchQueryService queryService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("workbench-query.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        insertCreationSnapshot();
        queryService = new SqliteWorkbenchQueryService(jdbc);
    }

    @Test
    void listByOwnerShouldFilterOwnerAndStatusAndUseStableDescendingOrder() {
        insertWorkbench("wb-a", "owner-1", "ACTIVE", BASE_TIME + 100, "Owner Active A");
        insertWorkbench("wb-b", "owner-1", "ACTIVE", BASE_TIME + 200, "Owner Active B");
        insertWorkbench("wb-c", "owner-1", "ACTIVE", BASE_TIME + 200, "Owner Active C");
        insertWorkbench("wb-archived", "owner-1", "ARCHIVED", BASE_TIME + 300, "Archived");
        insertWorkbench("wb-foreign", "owner-2", "ACTIVE", BASE_TIME + 400, "Foreign");
        jdbc.update("UPDATE workbench SET active_write_run_id=? WHERE id=?", "run-c", "wb-c");
        jdbc.update("UPDATE workbench_phase SET status='IN_PROGRESS', active_run_id=?, "
                        + "active_run_mode='MODIFY_WORKSPACE', active_run_prepared_at=? "
                        + "WHERE workbench_id=? AND phase='IMPLEMENT_TEST'",
                "run-c", BASE_TIME + 200, "wb-c");

        WorkbenchListPage page = queryService.listByOwner(
                "owner-1", new WorkbenchListRequest(WorkbenchStatus.ACTIVE, null, 10));

        assertEquals(Arrays.asList("wb-c", "wb-b", "wb-a"), ids(page.getItems()));
        assertNull(page.getNextCursor());
        WorkbenchListItemView first = page.getItems().get(0);
        assertEquals("Owner Active C", first.getTitle());
        assertEquals("CODEX", first.getAgentType());
        assertEquals("local", first.getEnvironment());
        assertEquals("agent-web", first.getPrimaryRepositoryKey());
        assertEquals(2, first.getRepositoryCount());
        assertEquals("run-c", first.getActiveWriteRunId());
        assertEquals(BASE_TIME + 200, first.getUpdatedAt());
        assertEquals(3L, first.getVersion());
    }

    @Test
    void listByOwnerShouldPageWithoutDuplicatesOrGapsWhenTimestampsTie() {
        insertWorkbench("wb-a", "owner-1", "ACTIVE", BASE_TIME + 100, "A");
        insertWorkbench("wb-b", "owner-1", "ACTIVE", BASE_TIME + 200, "B");
        insertWorkbench("wb-c", "owner-1", "ACTIVE", BASE_TIME + 200, "C");
        insertWorkbench("wb-d", "owner-1", "ACTIVE", BASE_TIME + 300, "D");
        insertWorkbench("wb-e", "owner-1", "ARCHIVED", BASE_TIME + 400, "E");

        WorkbenchListPage first = queryService.listByOwner(
                "owner-1", new WorkbenchListRequest(null, null, 2));
        WorkbenchListCursor cursor = first.getNextCursor();
        WorkbenchListPage second = queryService.listByOwner(
                "owner-1", new WorkbenchListRequest(null, cursor, 2));
        WorkbenchListPage third = queryService.listByOwner(
                "owner-1", new WorkbenchListRequest(null, second.getNextCursor(), 2));

        assertEquals(Arrays.asList("wb-e", "wb-d"), ids(first.getItems()));
        assertEquals(BASE_TIME + 300, cursor.getUpdatedAt());
        assertEquals("wb-d", cursor.getWorkbenchId());
        assertEquals(Arrays.asList("wb-c", "wb-b"), ids(second.getItems()));
        assertEquals(Collections.singletonList("wb-a"), ids(third.getItems()));
        assertNull(third.getNextCursor());
        List<String> allIds = Arrays.asList(first, second, third).stream()
                .flatMap(page -> page.getItems().stream())
                .map(WorkbenchListItemView::getId)
                .collect(Collectors.toList());
        assertEquals(5, allIds.stream().distinct().count());
    }

    @Test
    void listByOwnerShouldReturnEmptyPageAndRejectUnsafePageSize() {
        WorkbenchListPage page = queryService.listByOwner(
                "owner-without-data", new WorkbenchListRequest(null, null, 20));

        assertTrue(page.getItems().isEmpty());
        assertNull(page.getNextCursor());
        assertThrows(UnsupportedOperationException.class,
                () -> page.getItems().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new WorkbenchListRequest(null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkbenchListRequest(null, null, 101));
    }

    @Test
    void cursorAndOwnerQueriesShouldRejectInvalidIdentitiesBeforeAccessingSqlite() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkbenchListCursor(-1L, "wb-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkbenchListCursor(BASE_TIME, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkbenchListCursor(BASE_TIME, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkbenchListCursor(BASE_TIME, repeatText('w', 129)));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.listByOwner(null, new WorkbenchListRequest(null, null, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.listByOwner(" ", new WorkbenchListRequest(null, null, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.findDetailByOwner(" ", "wb-1"));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.findDetailByOwner("owner-1", " "));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.findDetailByOwner("owner-1", null));
    }

    @Test
    void findDetailByOwnerShouldProjectSafeScopeFourPhasesConversationHistoryAndActiveRuns() {
        insertWorkbench("wb-detail", "owner-1", "ACTIVE", BASE_TIME + 500, "Workbench Detail");
        jdbc.update("UPDATE workbench SET original_goal=?, active_write_run_id=?, version=? WHERE id=?",
                "完成 Workbench CQRS", "review-run", 7L, "wb-detail");
        jdbc.update("UPDATE workbench_phase SET status='IN_PROGRESS', conversation_generation=1, "
                        + "last_activity_at=? WHERE workbench_id=? AND phase='REQUIREMENT_ANALYSIS'",
                BASE_TIME + 410, "wb-detail");
        insertConversation("wb-detail", "REQUIREMENT_ANALYSIS", 0, "analysis-v0",
                BASE_TIME + 10, Long.valueOf(BASE_TIME + 20));
        insertConversation("wb-detail", "REQUIREMENT_ANALYSIS", 1, "analysis-v1",
                BASE_TIME + 30, null);
        jdbc.update("UPDATE workbench_phase SET status='IN_PROGRESS', active_run_id=?, "
                        + "active_run_mode='DISCUSS_READ_ONLY', active_run_prepared_at=?, last_activity_at=? "
                        + "WHERE workbench_id=? AND phase='SOLUTION_DESIGN'",
                "solution-run", BASE_TIME + 420, BASE_TIME + 420, "wb-detail");
        jdbc.update("UPDATE workbench_phase SET status='HUMAN_COMPLETED', completed_at=?, "
                        + "last_activity_at=? WHERE workbench_id=? AND phase='IMPLEMENT_TEST'",
                BASE_TIME + 430, BASE_TIME + 430, "wb-detail");
        jdbc.update("UPDATE workbench_phase SET status='IN_PROGRESS', active_run_id=?, "
                        + "active_run_mode='MODIFY_WORKSPACE', active_run_prepared_at=?, "
                        + "review_confirmation_id=?, review_opinion_version=?, review_opinion_hash=?, "
                        + "last_activity_at=? WHERE workbench_id=? AND phase='REVIEW_REFACTOR'",
                "review-run", BASE_TIME + 440, "confirmation-7", 7L, HASH_D,
                BASE_TIME + 440, "wb-detail");

        Optional<WorkbenchDetailView> found = queryService.findDetailByOwner("owner-1", "wb-detail");

        assertTrue(found.isPresent());
        WorkbenchDetailView detail = found.get();
        assertEquals("wb-detail", detail.getId());
        assertEquals("Workbench Detail", detail.getTitle());
        assertEquals("完成 Workbench CQRS", detail.getOriginalGoal());
        assertEquals("review-run", detail.getActiveWriteRunId());
        assertEquals(7L, detail.getVersion());
        assertEquals("agent-web", detail.getRepositoryScope().getPrimaryRepositoryKey());
        assertEquals(HASH_A, detail.getRepositoryScope().getScopeHash());
        assertEquals(2, detail.getRepositoryScope().getRepositories().size());
        assertEquals("service-api",
                detail.getRepositoryScope().getRepositories().get(1).getRepositoryKey());
        assertFalse(fieldNames(WorkbenchDetailView.class).contains("workspaceRoot"));
        assertFalse(fieldNames(WorkbenchDetailView.RepositoryView.class)
                .contains("repositoryRoot"));
        assertFalse(fieldNames(WorkbenchDetailView.RepositoryView.class)
                .contains("rootFingerprint"));
        assertEquals("snapshot-1", detail.getCreationSnapshot().getSnapshotId());
        assertEquals(HASH_B, detail.getCreationSnapshot().getTopologyHash());
        assertEquals(HASH_C, detail.getCreationSnapshot().getStateHash());
        assertEquals(2, detail.getCreationSnapshot().getRepositoryCount());
        assertEquals(4, detail.getPhases().size());
        assertThrows(UnsupportedOperationException.class,
                () -> detail.getPhases().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> detail.getRepositoryScope().getRepositories().clear());
        assertEquals(Arrays.asList(
                        "REQUIREMENT_ANALYSIS", "SOLUTION_DESIGN", "IMPLEMENT_TEST", "REVIEW_REFACTOR"),
                detail.getPhases().stream().map(WorkbenchDetailView.PhaseView::getPhase)
                        .collect(Collectors.toList()));

        WorkbenchDetailView.PhaseView analysis = detail.getPhases().get(0);
        assertEquals(1, analysis.getConversationGeneration());
        assertEquals("analysis-v1", analysis.getCurrentConversation().getSessionId());
        assertEquals(Collections.singletonList("analysis-v0"),
                analysis.getConversationHistory().stream()
                        .map(WorkbenchDetailView.ConversationView::getSessionId)
                        .collect(Collectors.toList()));
        assertNull(analysis.getActiveRun());

        WorkbenchDetailView.PhaseView solution = detail.getPhases().get(1);
        assertEquals("solution-run", solution.getActiveRun().getRunId());
        assertEquals("DISCUSS_READ_ONLY", solution.getActiveRun().getRunMode());
        assertNull(solution.getActiveRun().getReviewConfirmationId());

        WorkbenchDetailView.PhaseView review = detail.getPhases().get(3);
        assertEquals("review-run", review.getActiveRun().getRunId());
        assertEquals("confirmation-7", review.getActiveRun().getReviewConfirmationId());
        assertEquals(7L, review.getActiveRun().getReviewOpinionVersion().longValue());
        assertEquals(HASH_D, review.getActiveRun().getReviewOpinionHash());
    }

    @Test
    void findDetailByOwnerShouldReturnSameEmptyResultForForeignOwnerAndMissingId() {
        insertWorkbench("wb-private", "owner-2", "ACTIVE", BASE_TIME + 100, "Private");

        Optional<WorkbenchDetailView> foreign =
                queryService.findDetailByOwner("owner-1", "wb-private");
        Optional<WorkbenchDetailView> missing =
                queryService.findDetailByOwner("owner-1", "wb-missing");

        assertFalse(foreign.isPresent());
        assertFalse(missing.isPresent());
        assertEquals(foreign, missing);
    }

    private void insertCreationSnapshot() {
        jdbc.update("INSERT INTO workspace_snapshot (snapshot_id, purpose, workspace_root, "
                        + "primary_repository_key, topology_hash, clean, state_hash, capture_started_at, "
                        + "captured_at, repository_count, anomaly_count) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "snapshot-1", "WORKBENCH_CREATE", "/secret/workspace", "agent-web",
                HASH_B, 1, HASH_C, BASE_TIME - 200, BASE_TIME - 100, 2, 0);
    }

    private void insertWorkbench(String id, String ownerId, String status,
                                 long updatedAt, String title) {
        jdbc.update("INSERT INTO workbench (id, owner_id, owner_name, title, original_goal, agent_type, "
                        + "environment, workspace_root, primary_repository_key, repository_scope_hash, "
                        + "creation_snapshot_id, creation_snapshot_topology_hash, "
                        + "creation_snapshot_state_hash, creation_snapshot_repository_count, "
                        + "active_write_run_id, status, created_at, updated_at, version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ownerId, "Owner " + ownerId, title, "Goal " + id, "CODEX", "local",
                "/secret/workspace", "agent-web", HASH_A, "snapshot-1", HASH_B, HASH_C, 2,
                null, status, updatedAt - 50, updatedAt, 3L);
        insertScope(id, "agent-web", 1);
        insertScope(id, "service-api", 0);
        insertPhase(id, "REQUIREMENT_ANALYSIS", 0);
        insertPhase(id, "SOLUTION_DESIGN", 1);
        insertPhase(id, "IMPLEMENT_TEST", 2);
        insertPhase(id, "REVIEW_REFACTOR", 3);
    }

    private void insertScope(String workbenchId, String repositoryKey, int primary) {
        jdbc.update("INSERT INTO workbench_repository_scope (workbench_id, repository_key, "
                        + "relative_path, repository_root, root_fingerprint, primary_repository) "
                        + "VALUES (?,?,?,?,?,?)",
                workbenchId, repositoryKey, repositoryKey,
                "/secret/workspace/" + repositoryKey, HASH_E, primary);
    }

    private void insertPhase(String workbenchId, String phase, int phaseOrder) {
        jdbc.update("INSERT INTO workbench_phase (workbench_id, phase, phase_order, status, "
                        + "conversation_generation) VALUES (?,?,?,?,?)",
                workbenchId, phase, phaseOrder, "NOT_STARTED", 0);
    }

    private void insertConversation(String workbenchId, String phase, int generation,
                                    String sessionId, long createdAt, Long retiredAt) {
        jdbc.update("INSERT INTO workbench_phase_conversation (workbench_id, phase, generation, "
                        + "session_id, created_by_id, created_by_name, created_at, retired_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                workbenchId, phase, generation, sessionId,
                "owner-1", "Owner One", createdAt, retiredAt);
    }

    private static List<String> ids(List<WorkbenchListItemView> items) {
        return items.stream().map(WorkbenchListItemView::getId).collect(Collectors.toList());
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(field -> field.getName())
                .collect(Collectors.toList());
    }

    private static String repeat(char value) {
        return repeatText(value, 64);
    }

    private static String repeatText(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }
}
