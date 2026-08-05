package com.example.agentweb.infra.workbench.query;

import com.example.agentweb.app.workbench.query.WorkbenchDetailView;
import com.example.agentweb.app.workbench.query.WorkbenchListCursor;
import com.example.agentweb.app.workbench.query.WorkbenchListItemView;
import com.example.agentweb.app.workbench.query.WorkbenchListPage;
import com.example.agentweb.app.workbench.query.WorkbenchListRequest;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessagePage;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessageRequest;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessageTooLargeException;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.infra.SqliteInitializer;
import com.example.agentweb.infra.workbench.WorkbenchStageSnapshotJsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-only Workbench Owner 读模型的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchQueryServiceTest {

    private static final long BASE_TIME = 1_785_561_600_000L;
    private static final String HASH_A = repeat('a');
    private static final String HASH_B = repeat('b');
    private static final String HASH_C = repeat('c');
    private static final String HASH_E = repeat('e');

    @TempDir
    Path temporaryDirectory;

    private JdbcTemplate jdbc;
    private SqliteWorkbenchQueryService queryService;
    private WorkbenchStageSnapshotJsonMapper stageSnapshotJsonMapper;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:"
                + temporaryDirectory.resolve("workbench-query.db")
                .toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        insertCreationSnapshot();
        queryService = new SqliteWorkbenchQueryService(jdbc);
        stageSnapshotJsonMapper =
                new WorkbenchStageSnapshotJsonMapper(new ObjectMapper());
    }

    @Test
    void should_ListOnlyOwnerWorkbenchesInStableDescendingOrder() {
        insertWorkbench("wb-a", "owner-1", "ACTIVE", BASE_TIME + 100, "A");
        insertWorkbench("wb-b", "owner-1", "ACTIVE", BASE_TIME + 200, "B");
        insertWorkbench("wb-c", "owner-1", "ACTIVE", BASE_TIME + 200, "C");
        insertWorkbench(
                "wb-archived", "owner-1", "ARCHIVED",
                BASE_TIME + 300, "Archived");
        insertWorkbench(
                "wb-foreign", "owner-2", "ACTIVE",
                BASE_TIME + 400, "Foreign");

        WorkbenchListPage page = queryService.listByOwner(
                "owner-1", new WorkbenchListRequest(
                        WorkbenchStatus.ACTIVE, null, 10));

        assertEquals(Arrays.asList("wb-c", "wb-b", "wb-a"),
                ids(page.getItems()));
        assertNull(page.getNextCursor());
        assertEquals(2, page.getItems().get(0).getRepositoryCount());
    }

    @Test
    void should_PageWorkbenchListWithoutDuplicatesWhenTimestampsTie() {
        insertWorkbench("wb-a", "owner-1", "ACTIVE", BASE_TIME + 100, "A");
        insertWorkbench("wb-b", "owner-1", "ACTIVE", BASE_TIME + 200, "B");
        insertWorkbench("wb-c", "owner-1", "ACTIVE", BASE_TIME + 200, "C");
        insertWorkbench("wb-d", "owner-1", "ACTIVE", BASE_TIME + 300, "D");

        WorkbenchListPage first = queryService.listByOwner(
                "owner-1", new WorkbenchListRequest(null, null, 2));
        WorkbenchListPage second = queryService.listByOwner(
                "owner-1", new WorkbenchListRequest(
                        null, first.getNextCursor(), 2));

        assertEquals(Arrays.asList("wb-d", "wb-c"), ids(first.getItems()));
        assertEquals(Arrays.asList("wb-b", "wb-a"), ids(second.getItems()));
        assertNull(second.getNextCursor());
    }

    @Test
    void should_RejectUnsafeOwnerAndCursorInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkbenchListCursor(-1L, "wb-1"));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.listByOwner(
                        " ", new WorkbenchListRequest(null, null, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.findDetailByOwner("owner-1", " "));
    }

    @Test
    void should_ProjectOnlyDynamicStagesInOwnerDetail() {
        insertDynamicWorkbench(
                "wb-detail", "owner-1", "stage-analysis", 1);
        insertDynamicStage(
                "wb-detail", "stage-implementation",
                "implementation", 20, 0,
                Set.of(RunMode.DISCUSS_READ_ONLY,
                        RunMode.MODIFY_WORKSPACE));
        insertStageConversation(
                "wb-detail", "stage-analysis", 0,
                "analysis-v0", BASE_TIME + 10,
                Long.valueOf(BASE_TIME + 20));
        insertStageConversation(
                "wb-detail", "stage-analysis", 1,
                "analysis-v1", BASE_TIME + 30, null);
        jdbc.update("UPDATE workbench_stage SET active_run_id=?, "
                        + "active_run_mode=?, active_run_prepared_at=? "
                        + "WHERE workbench_id=? "
                        + "AND stage_instance_identifier=?",
                "stage-run", "DISCUSS_READ_ONLY", BASE_TIME + 40,
                "wb-detail", "stage-analysis");

        WorkbenchDetailView detail = queryService.findDetailByOwner(
                "owner-1", "wb-detail").orElseThrow(AssertionError::new);

        assertEquals(2, detail.getStages().size());
        assertEquals(Arrays.asList("analysis", "implementation"),
                detail.getStages().stream()
                        .map(WorkbenchDetailView.StageView::
                                getDefinitionIdentifier)
                        .collect(Collectors.toList()));
        WorkbenchDetailView.StageView analysis = detail.getStages().get(0);
        assertEquals(Collections.singletonList("DISCUSS_READ_ONLY"),
                analysis.getAllowedRunModes());
        assertEquals(Arrays.asList(
                        "DISCUSS_READ_ONLY", "MODIFY_WORKSPACE"),
                detail.getStages().get(1).getAllowedRunModes());
        assertEquals("analysis-v1",
                analysis.getCurrentConversation().getSessionId());
        assertEquals(Collections.singletonList("analysis-v0"),
                analysis.getConversationHistory().stream()
                        .map(WorkbenchDetailView.ConversationView::getSessionId)
                        .collect(Collectors.toList()));
        assertEquals("stage-run", analysis.getActiveRun().getRunId());
    }

    @Test
    void should_ObscureForeignOrMissingWorkbenchDetail() {
        insertWorkbench(
                "wb-private", "owner-2", "ACTIVE",
                BASE_TIME + 100, "Private");

        Optional<WorkbenchDetailView> foreign =
                queryService.findDetailByOwner("owner-1", "wb-private");
        Optional<WorkbenchDetailView> missing =
                queryService.findDetailByOwner("owner-1", "wb-missing");

        assertFalse(foreign.isPresent());
        assertEquals(foreign, missing);
    }

    @Test
    void should_BindStageConversationToOwnerGenerationSessionAndRunOrigin() {
        insertDynamicWorkbench(
                "wb-stage-chat", "owner-1", "stage-analysis", 1);
        insertStageChatSession(
                "stage-analysis-v0", "wb-stage-chat:stage-analysis",
                "owner-1", "Owner owner-1");
        insertStageChatSession(
                "stage-analysis-v1", "wb-stage-chat:stage-analysis",
                "owner-1", "Owner owner-1");
        insertStageConversation(
                "wb-stage-chat", "stage-analysis", 0,
                "stage-analysis-v0", BASE_TIME + 10,
                Long.valueOf(BASE_TIME + 20));
        insertStageConversation(
                "wb-stage-chat", "stage-analysis", 1,
                "stage-analysis-v1", BASE_TIME + 30, null);
        long userMessage = insertMessage(
                "stage-analysis-v1", "user", "当前问题", BASE_TIME + 31);
        long assistantMessage = insertMessage(
                "stage-analysis-v1", "assistant", "当前回答",
                BASE_TIME + 32);
        insertWorkbenchRun(
                "stage-run-current", "stage-analysis-v1", userMessage,
                Long.valueOf(assistantMessage),
                "wb-stage-chat:stage-analysis", "stage-run-current");

        WorkbenchStageConversationMessagePage page =
                queryService.findCurrentStageConversationByOwner(
                        "owner-1", "wb-stage-chat", "stage-analysis",
                        new WorkbenchStageConversationMessageRequest(null, 1))
                        .orElseThrow(AssertionError::new);

        assertEquals("stage-analysis-v1", page.getSessionId());
        assertEquals(1, page.getGeneration());
        assertEquals(assistantMessage,
                page.getMessages().get(0).getMessageId());
        assertEquals("stage-run-current",
                page.getMessages().get(0).getRunId());
        assertEquals(Long.valueOf(assistantMessage), page.getNextCursor());
    }

    @Test
    void should_ObscureForeignOrUnknownStageConversation() {
        insertDynamicWorkbench(
                "wb-stage-private", "owner-2", "stage-private", 0);
        insertDynamicWorkbench(
                "wb-stage-empty", "owner-1", "stage-empty", 0);

        Optional<WorkbenchStageConversationMessagePage> foreign =
                queryService.findCurrentStageConversationByOwner(
                        "owner-1", "wb-stage-private", "stage-private");
        Optional<WorkbenchStageConversationMessagePage> missing =
                queryService.findCurrentStageConversationByOwner(
                        "owner-1", "wb-stage-empty", "stage-missing");
        WorkbenchStageConversationMessagePage empty =
                queryService.findCurrentStageConversationByOwner(
                        "owner-1", "wb-stage-empty", "stage-empty")
                        .orElseThrow(AssertionError::new);

        assertFalse(foreign.isPresent());
        assertEquals(foreign, missing);
        assertNull(empty.getSessionId());
        assertTrue(empty.getMessages().isEmpty());
    }

    @Test
    void should_RejectCorruptStageSessionOwnerBinding() {
        insertDynamicWorkbench(
                "wb-stage-corrupt", "owner-1", "stage-corrupt", 0);
        insertStageChatSession(
                "foreign-session", "wb-stage-corrupt:stage-corrupt",
                "owner-2", "Foreign Owner");
        insertStageConversation(
                "wb-stage-corrupt", "stage-corrupt", 0,
                "foreign-session", BASE_TIME + 10, null);

        assertThrows(IllegalStateException.class,
                () -> queryService.findCurrentStageConversationByOwner(
                        "owner-1", "wb-stage-corrupt", "stage-corrupt"));
    }

    @Test
    void should_RejectOversizedStageConversationMessage() {
        insertDynamicWorkbench(
                "wb-stage-large", "owner-1", "stage-large", 0);
        insertStageChatSession(
                "stage-large-v0", "wb-stage-large:stage-large",
                "owner-1", "Owner owner-1");
        insertStageConversation(
                "wb-stage-large", "stage-large", 0,
                "stage-large-v0", BASE_TIME + 10, null);
        insertMessage(
                "stage-large-v0", "assistant",
                repeatText('x', 1024 * 1024 + 1), BASE_TIME + 11);

        assertThrows(WorkbenchStageConversationMessageTooLargeException.class,
                () -> queryService.findCurrentStageConversationByOwner(
                        "owner-1", "wb-stage-large", "stage-large"));
    }

    private void insertCreationSnapshot() {
        jdbc.update("INSERT INTO workspace_snapshot "
                        + "(snapshot_id, purpose, workspace_root, "
                        + "primary_repository_key, topology_hash, clean, "
                        + "state_hash, capture_started_at, captured_at, "
                        + "repository_count, anomaly_count) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "snapshot-1", "WORKBENCH_CREATE", "/secret/workspace",
                "agent-web", HASH_B, 1, HASH_C,
                BASE_TIME - 200, BASE_TIME - 100, 2, 0);
    }

    private void insertWorkbench(
            String id, String ownerId, String status,
            long updatedAt, String title) {
        insertWorkbenchRoot(id, ownerId, status, updatedAt, title);
        insertDynamicStage(id, "stage-" + id, "definition-" + id, 10, 0);
    }

    private void insertDynamicWorkbench(
            String id, String ownerId, String stageInstanceIdentifier,
            int conversationGeneration) {
        insertWorkbenchRoot(
                id, ownerId, "ACTIVE", BASE_TIME + 500, "Dynamic " + id);
        insertDynamicStage(
                id, stageInstanceIdentifier, "analysis", 10,
                conversationGeneration);
    }

    private void insertWorkbenchRoot(
            String id, String ownerId, String status,
            long updatedAt, String title) {
        jdbc.update("INSERT INTO workbench "
                        + "(id, owner_id, owner_name, title, original_goal, "
                        + "agent_type, environment, workspace_root, "
                        + "primary_repository_key, repository_scope_hash, "
                        + "creation_snapshot_id, "
                        + "creation_snapshot_topology_hash, "
                        + "creation_snapshot_state_hash, "
                        + "creation_snapshot_repository_count, "
                        + "active_write_run_id, status, created_at, "
                        + "updated_at, version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ownerId, "Owner " + ownerId, title, "Goal " + id,
                "CODEX", "local", "/secret/workspace", "agent-web",
                HASH_A, "snapshot-1", HASH_B, HASH_C, 2, null, status,
                updatedAt - 50, updatedAt, 3L);
        insertScope(id, "agent-web", 1);
        insertScope(id, "service-api", 0);
    }

    private void insertDynamicStage(
            String workbenchId, String stageInstanceIdentifier,
            String definitionIdentifier, int sequenceNumber,
            int conversationGeneration) {
        insertDynamicStage(
                workbenchId, stageInstanceIdentifier,
                definitionIdentifier, sequenceNumber,
                conversationGeneration,
                Set.of(RunMode.DISCUSS_READ_ONLY));
    }

    private void insertDynamicStage(
            String workbenchId, String stageInstanceIdentifier,
            String definitionIdentifier, int sequenceNumber,
            int conversationGeneration, Set<RunMode> allowedRunModes) {
        WorkbenchStageSnapshot snapshot = stageSnapshot(
                definitionIdentifier, sequenceNumber, allowedRunModes);
        jdbc.update("INSERT INTO workbench_stage "
                        + "(workbench_id, stage_instance_identifier, "
                        + "definition_identifier, definition_revision, "
                        + "definition_hash, sequence_number, "
                        + "stage_snapshot_json, stage_snapshot_hash, "
                        + "status, conversation_generation) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                workbenchId, stageInstanceIdentifier,
                snapshot.getDefinitionIdentifier(),
                snapshot.getDefinitionRevision(),
                snapshot.getDefinitionHash(), snapshot.getSequenceNumber(),
                stageSnapshotJsonMapper.write(snapshot),
                snapshot.getSnapshotHash(), "IN_PROGRESS",
                conversationGeneration);
    }

    private WorkbenchStageSnapshot stageSnapshot(
            String definitionIdentifier, int sequenceNumber) {
        return stageSnapshot(
                definitionIdentifier, sequenceNumber,
                Set.of(RunMode.DISCUSS_READ_ONLY));
    }

    private WorkbenchStageSnapshot stageSnapshot(
            String definitionIdentifier, int sequenceNumber,
            Set<RunMode> allowedRunModes) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor editor = StageCatalogEditor.create(
                "admin-1", "Admin");
        catalog.createDraft(
                definitionIdentifier,
                WorkbenchStageDraftContent.create(
                        sequenceNumber, definitionIdentifier,
                        "Stage description", "Stage rules",
                        allowedRunModes,
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, Instant.ofEpochMilli(BASE_TIME - 20));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        definitionIdentifier, catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        editor, Instant.ofEpochMilli(BASE_TIME - 10)));
    }

    private void insertScope(
            String workbenchId, String repositoryKey, int primary) {
        jdbc.update("INSERT INTO workbench_repository_scope "
                        + "(workbench_id, repository_key, relative_path, "
                        + "repository_root, root_fingerprint, "
                        + "primary_repository) VALUES (?,?,?,?,?,?)",
                workbenchId, repositoryKey, repositoryKey,
                "/secret/workspace/" + repositoryKey, HASH_E, primary);
    }

    private void insertStageConversation(
            String workbenchId, String stageInstanceIdentifier,
            int generation, String sessionId,
            long createdAt, Long retiredAt) {
        jdbc.update("INSERT INTO workbench_stage_conversation "
                        + "(workbench_id, stage_instance_identifier, "
                        + "generation, session_id, created_by_id, "
                        + "created_by_name, created_at, retired_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                workbenchId, stageInstanceIdentifier, generation, sessionId,
                "owner-1", "Owner owner-1", createdAt, retiredAt);
    }

    private void insertStageChatSession(
            String sessionId, String contextId,
            String ownerId, String ownerName) {
        jdbc.update("INSERT INTO chat_session "
                        + "(id, agent_type, working_dir, created_at, "
                        + "session_kind, context_id, user_id, user_name) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                sessionId, "CODEX", "/secret/workspace/agent-web",
                "2026-08-05T00:00:00Z", "WORKBENCH_STAGE", contextId,
                ownerId, ownerName);
    }

    private long insertMessage(
            String sessionId, String role, String content, long timestamp) {
        jdbc.update("INSERT INTO chat_message "
                        + "(session_id, role, content, timestamp) "
                        + "VALUES (?,?,?,?)",
                sessionId, role, content,
                Instant.ofEpochMilli(timestamp).toString());
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM chat_message", Long.class)
                .longValue();
    }

    private void insertWorkbenchRun(
            String runId, String sessionId, long userMessageId,
            Long assistantMessageId, String originReference,
            String executionContextId) {
        jdbc.update("INSERT INTO chat_run "
                        + "(id, session_id, user_message_id, "
                        + "assistant_message_id, idempotency_key, "
                        + "recall_enabled, run_origin, origin_reference, "
                        + "execution_context_id, status, last_event_seq, "
                        + "created_at, updated_at, version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, sessionId, userMessageId, assistantMessageId,
                "key-" + runId, 0, "WORKBENCH", originReference,
                executionContextId, "SUCCEEDED", 2,
                BASE_TIME + 31, BASE_TIME + 42, 1L);
    }

    private static List<String> ids(
            List<WorkbenchListItemView> items) {
        return items.stream().map(WorkbenchListItemView::getId)
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
