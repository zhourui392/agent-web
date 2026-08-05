package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceipt;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 动态 Stage Conversation restart 收据的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchStageConversationRestartReceiptRepositoryTest {

    private static final String STAGE_INSTANCE_IDENTIFIER =
            "stage-implementation";

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private Workbench workbench;
    private SqliteWorkbenchStageConversationRestartReceiptRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("stage-conversation-receipt.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "stage-conversation-receipt-snapshot");
        workbench = Workbench.create(
                WorkbenchId.of("workbench-stage-conversation-receipt"), OWNER,
                "Stage Receipt", "验证动态 Stage restart 收据",
                AgentType.CODEX, "local", workspace.scope(),
                workspace.snapshot().reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_IDENTIFIER, stageSnapshot())),
                NOW.plusMillis(30));
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        insertStageSession("stage-session-0");
        insertStageSession("stage-session-1");
        insertStageSession("stage-session-2");
        repository =
                new SqliteWorkbenchStageConversationRestartReceiptRepository(
                        jdbc);
    }

    @Test
    void addAndFindShouldRoundTripSafeStageReceipt() {
        // Given
        WorkbenchStageConversationRestartReceipt receipt = receipt(
                "restart-key-1", "stage-session-1");

        // When
        repository.add(receipt);
        WorkbenchStageConversationRestartReceipt restored = repository
                .findByOwnerAndIdempotencyKey(OWNER, "restart-key-1")
                .orElseThrow(AssertionError::new);

        // Then
        assertEquals(receipt, restored);
        assertFalse(repository.findByOwnerAndIdempotencyKey(
                OwnerReference.of("foreign", "Other"),
                "restart-key-1").isPresent());
        List<Map<String, Object>> columns = jdbc.queryForList(
                "PRAGMA table_info("
                        + "workbench_stage_conversation_restart_receipt)");
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("name")).toLowerCase();
            assertFalse(name.contains("path"));
            assertFalse(name.contains("secret"));
            assertFalse(name.contains("token"));
        }
    }

    @Test
    void ownerAndIdempotencyKeyShouldBeUnique() {
        // Given
        repository.add(receipt("restart-key-1", "stage-session-1"));

        // When / Then
        assertThrows(DataAccessException.class,
                () -> repository.add(receipt(
                        "restart-key-1", "stage-session-2")));
        assertEquals("stage-session-1", repository
                .findByOwnerAndIdempotencyKey(OWNER, "restart-key-1")
                .orElseThrow(AssertionError::new)
                .getSessionId());
    }

    private WorkbenchStageConversationRestartReceipt receipt(
            String key, String sessionId) {
        return WorkbenchStageConversationRestartReceipt.record(
                OWNER, key, workbench.getId(), STAGE_INSTANCE_IDENTIFIER,
                "stage-session-0", sessionId, 1, 4L,
                Instant.parse("2026-08-05T10:00:00Z"));
    }

    private void insertStageSession(String sessionId) {
        jdbc.update("INSERT INTO chat_session (id, agent_type, working_dir, "
                        + "created_at, session_kind, context_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, "CODEX", "/workspace/agent-web",
                "2026-08-05T09:00:00Z", "WORKBENCH_STAGE",
                workbench.getId().getValue() + ":"
                        + STAGE_INSTANCE_IDENTIFIER);
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft("implementation", WorkbenchStageDraftContent.create(
                        10, "开发测试", "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "implementation", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(1));
        return WorkbenchStageSnapshot.fromPublishedRevision(revision);
    }
}
