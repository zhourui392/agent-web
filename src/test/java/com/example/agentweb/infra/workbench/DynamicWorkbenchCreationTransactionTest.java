package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.DefaultWorkbenchCreationCommitter;
import com.example.agentweb.app.workbench.PreparedWorkbenchCreation;
import com.example.agentweb.app.workbench.TransactionalWorkbenchCreation;
import com.example.agentweb.app.workbench.WorkbenchCreationResult;
import com.example.agentweb.app.workbench.query.WorkbenchDetailView;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchCreationReceipt;
import com.example.agentweb.domain.workbench.WorkbenchCreationRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.infra.workspace.SqliteWorkspaceSnapshotRepository;
import com.example.agentweb.infra.workbench.query.SqliteWorkbenchQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_E;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 Workbench 创建期强一致事实的真实 SQLite 事务测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class DynamicWorkbenchCreationTransactionTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private SqliteWorkbenchRepository workbenchRepository;
    private SqliteWorkspaceSnapshotRepository snapshotRepository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("dynamic-creation-transaction.db"));
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbc, tempDir, "dynamic-creation-snapshot");
        jdbc.update("DELETE FROM workspace_snapshot WHERE snapshot_id=?",
                workspace.snapshot().getSnapshotId());
        workbenchRepository = new SqliteWorkbenchRepository(jdbc);
        snapshotRepository = new SqliteWorkspaceSnapshotRepository(jdbc);
    }

    @Test
    void should_CommitSnapshotWorkbenchStagesAndReceipt_InOneTransaction() {
        // Given
        Workbench workbench = dynamicWorkbench("workbench-dynamic-commit");
        WorkbenchCreationReceipt receipt = receipt(workbench, "dynamic-create-key");
        SqliteWorkbenchCreationRepository receiptRepository =
                new SqliteWorkbenchCreationRepository(jdbc);
        DefaultWorkbenchCreationCommitter committer = committer(receiptRepository);

        // When
        WorkbenchCreationResult result = committer.commit(
                new PreparedWorkbenchCreation(
                        workbench, workspace.snapshot(), receipt));

        // Then
        assertFalse(result.isReplayed());
        assertEquals(workbench.getId().getValue(), result.getWorkbenchId());
        assertEquals(1, count("workspace_snapshot"));
        assertEquals(1, count("workbench"));
        assertEquals(2, count("workbench_stage"));
        assertEquals(1, count("workbench_creation_request"));
        Workbench restored = workbenchRepository.findById(workbench.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(List.of("requirement-analysis", "implementation"),
                restored.getStages().stream()
                        .map(stage -> stage.getSnapshot().getDefinitionIdentifier())
                        .toList());
        assertTrue(receiptRepository.findByOwnerAndIdempotencyKey(
                OWNER, receipt.getIdempotencyKey()).isPresent());
        WorkbenchDetailView detail = new SqliteWorkbenchQueryService(jdbc)
                .findDetailByOwner(OWNER.getOwnerId(), workbench.getId().getValue())
                .orElseThrow(AssertionError::new);
        assertEquals(List.of("requirement-analysis", "implementation"),
                detail.getStages().stream()
                        .map(WorkbenchDetailView.StageView::getDefinitionIdentifier)
                        .toList());
        assertEquals(List.of(10, 30), detail.getStages().stream()
                .map(WorkbenchDetailView.StageView::getSequenceNumber).toList());
        assertEquals("stage-requirement",
                detail.getStages().get(0).getStageInstanceIdentifier());
        assertEquals("需求分析", detail.getStages().get(0).getDisplayName());
        assertEquals("阶段说明", detail.getStages().get(0).getDescription());
    }

    @Test
    void should_RollBackSnapshotWorkbenchAndStages_When_ReceiptWriteFails() {
        // Given
        Workbench workbench = dynamicWorkbench("workbench-dynamic-rollback");
        WorkbenchCreationReceipt receipt = receipt(workbench, "rollback-create-key");
        WorkbenchCreationRepository failingReceiptRepository =
                new WorkbenchCreationRepository() {
                    @Override
                    public Optional<WorkbenchCreationReceipt>
                    findByOwnerAndIdempotencyKey(
                            OwnerReference owner, String idempotencyKey) {
                        return Optional.empty();
                    }

                    @Override
                    public void add(WorkbenchCreationReceipt candidate) {
                        throw new IllegalStateException("simulated receipt write failure");
                    }
                };
        DefaultWorkbenchCreationCommitter committer =
                committer(failingReceiptRepository);

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> committer.commit(new PreparedWorkbenchCreation(
                        workbench, workspace.snapshot(), receipt)));
        assertEquals(0, count("workspace_snapshot"));
        assertEquals(0, count("workbench"));
        assertEquals(0, count("workbench_repository_scope"));
        assertEquals(0, count("workbench_stage"));
        assertEquals(0, count("workbench_creation_request"));
        assertTrue(workbenchRepository.findById(workbench.getId()).isEmpty());
        assertTrue(snapshotRepository.findById(
                workspace.snapshot().getSnapshotId()).isEmpty());
    }

    @Test
    void should_ProjectDynamicStageCurrentConversationAndRetiredHistory() {
        // Given
        Workbench source = dynamicWorkbench("workbench-dynamic-detail-conversation");
        snapshotRepository.add(workspace.snapshot());
        workbenchRepository.add(source);
        Workbench bound = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        bound.bindStageConversation(
                "stage-implementation", "stage-session-0", OWNER,
                bound.getVersion(), NOW.plusSeconds(1));
        workbenchRepository.update(bound);
        Workbench completed = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        completed.completeStage(
                "stage-implementation", OWNER, completed.getVersion(),
                NOW.plusSeconds(2));
        workbenchRepository.update(completed);
        Workbench reopened = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        reopened.reopenStage(
                "stage-implementation", OWNER, reopened.getVersion(),
                NOW.plusSeconds(3));
        workbenchRepository.update(reopened);
        Workbench restarted = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        restarted.restartStageConversation(
                "stage-implementation", "stage-session-1", OWNER,
                restarted.getVersion(), NOW.plusSeconds(4));
        workbenchRepository.update(restarted);

        // When
        WorkbenchDetailView detail = new SqliteWorkbenchQueryService(jdbc)
                .findDetailByOwner(
                        OWNER.getOwnerId(), source.getId().getValue())
                .orElseThrow(AssertionError::new);

        // Then
        WorkbenchDetailView.StageView implementation = detail.getStages().get(1);
        assertEquals(1, implementation.getConversationGeneration());
        assertEquals("stage-session-1",
                implementation.getCurrentConversation().getSessionId());
        assertEquals(1, implementation.getConversationHistory().size());
        assertEquals("stage-session-0", implementation
                .getConversationHistory().get(0).getSessionId());
        assertEquals(NOW.plusSeconds(4).toEpochMilli(), implementation
                .getConversationHistory().get(0).getRetiredAt().longValue());
        assertTrue(detail.getStages().get(0).getConversationHistory().isEmpty());
    }

    private DefaultWorkbenchCreationCommitter committer(
            WorkbenchCreationRepository receiptRepository) {
        DataSource dataSource = jdbc.getDataSource();
        assertNotNull(dataSource);
        return new DefaultWorkbenchCreationCommitter(
                receiptRepository, workbenchRepository, snapshotRepository,
                new TransactionalWorkbenchCreation(
                        new DataSourceTransactionManager(dataSource)));
    }

    private Workbench dynamicWorkbench(String workbenchIdentifier) {
        WorkbenchStageSnapshot requirement = stageSnapshot(
                "requirement-analysis", 10, "需求分析");
        WorkbenchStageSnapshot implementation = stageSnapshot(
                "implementation", 30, "开发测试");
        return Workbench.create(
                WorkbenchId.of(workbenchIdentifier), OWNER,
                "Dynamic Workbench", "实现动态阶段",
                AgentType.CODEX, "local", workspace.scope(),
                workspace.snapshot().reference(),
                Arrays.asList(
                        WorkbenchStageState.initial(
                                "stage-implementation", implementation),
                        WorkbenchStageState.initial(
                                "stage-requirement", requirement)),
                NOW.plusMillis(30));
    }

    private WorkbenchStageSnapshot stageSnapshot(
            String definitionIdentifier, int sequenceNumber, String displayName) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(definitionIdentifier, WorkbenchStageDraftContent.create(
                        sequenceNumber, displayName, "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                definitionIdentifier, catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(1));
        return WorkbenchStageSnapshot.fromPublishedRevision(revision);
    }

    private WorkbenchCreationReceipt receipt(
            Workbench workbench, String idempotencyKey) {
        return WorkbenchCreationReceipt.record(
                OWNER, idempotencyKey, HASH_E, workbench.getId(),
                workbench.getCreatedAt());
    }

    private int count(String tableName) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName, Integer.class);
    }
}
