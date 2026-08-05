package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.StageCommandReference;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageMcpServerReference;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.StageSkillReference;
import com.example.agentweb.domain.workbench.stage.StageSkillSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Dynamic Stage Workbench 聚合、Repository Scope 与会话代际的真实 SQLite 测试。
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
    void should_RoundTripImmutableStageSnapshots() {
        // Given
        Workbench source = dynamicWorkbench("workbench-dynamic-round-trip");

        // When
        repository.add(source);
        Workbench restored = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);

        // Then
        assertEquals(2, restored.getStages().size());
        assertEquals(List.of("requirement-analysis", "implementation"),
                restored.getStages().stream()
                        .map(stage -> stage.getSnapshot().getDefinitionIdentifier())
                        .toList());
        for (int index = 0; index < source.getStages().size(); index++) {
            assertStage(source.getStages().get(index), restored.getStages().get(index));
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_stage WHERE workbench_id=?",
                Integer.class, source.getId().getValue()));
    }

    @Test
    void should_RoundTripDynamicStageCompletionAndReopen_WithWorkbenchVersion() {
        // Given
        Workbench source = dynamicWorkbench("workbench-dynamic-lifecycle");
        repository.add(source);
        Workbench toComplete = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        long initialVersion = toComplete.getVersion();

        // When
        toComplete.completeStage(
                "stage-implementation", OWNER, initialVersion, NOW.plusSeconds(1));
        repository.update(toComplete);

        // Then
        Workbench completed = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(WorkbenchStageStatus.HUMAN_COMPLETED,
                completed.stage("stage-implementation").getStatus());
        assertEquals(NOW.plusSeconds(1),
                completed.stage("stage-implementation").getCompletedAt());
        assertEquals(initialVersion + 1L, completed.getVersion());
        assertEquals("HUMAN_COMPLETED", jdbc.queryForObject(
                "SELECT status FROM workbench_stage "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                String.class, source.getId().getValue(), "stage-implementation"));
        assertEquals(NOW.plusSeconds(1).toEpochMilli(), jdbc.queryForObject(
                "SELECT completed_at FROM workbench_stage "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                Long.class, source.getId().getValue(), "stage-implementation"));

        // When
        completed.reopenStage(
                "stage-implementation", OWNER, completed.getVersion(),
                NOW.plusSeconds(2));
        repository.update(completed);

        // Then
        Workbench reopened = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(WorkbenchStageStatus.NOT_STARTED,
                reopened.stage("stage-implementation").getStatus());
        assertNull(reopened.stage("stage-implementation").getCompletedAt());
        assertEquals(initialVersion + 2L, reopened.getVersion());
        assertNull(jdbc.queryForObject(
                "SELECT completed_at FROM workbench_stage "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                Long.class, source.getId().getValue(), "stage-implementation"));
    }

    @Test
    void should_RoundTripIndependentDynamicStageConversationHistory() {
        // Given
        Workbench source = dynamicWorkbench("workbench-dynamic-conversation");
        repository.add(source);
        Workbench changed = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        changed.bindStageConversation(
                "stage-implementation", "stage-session-0", OWNER,
                0L, NOW.plusSeconds(1));
        repository.update(changed);
        Workbench completed = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertNotNull(completed.stage("stage-implementation")
                .currentConversation());
        completed.completeStage(
                "stage-implementation", OWNER, completed.getVersion(),
                NOW.plusSeconds(2));
        repository.update(completed);
        Workbench reopened = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        reopened.reopenStage(
                "stage-implementation", OWNER, reopened.getVersion(),
                NOW.plusSeconds(3));
        repository.update(reopened);
        Workbench restarted = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        restarted.restartStageConversation(
                "stage-implementation", "stage-session-1", OWNER,
                restarted.getVersion(), NOW.plusSeconds(4));

        // When
        repository.update(restarted);
        Workbench restored = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);

        // Then
        WorkbenchStageState implementation = restored.stage(
                "stage-implementation");
        assertEquals(WorkbenchStageStatus.IN_PROGRESS,
                implementation.getStatus());
        assertEquals(1, implementation.getConversationGeneration());
        assertEquals(2, implementation.getConversationHistory().size());
        assertEquals("stage-session-0", implementation
                .getConversationHistory().get(0).getConversationId());
        assertEquals(NOW.plusSeconds(4), implementation
                .getConversationHistory().get(0).getRetiredAt());
        assertEquals("stage-session-1",
                implementation.currentConversation().getConversationId());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_stage_conversation "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                Integer.class, source.getId().getValue(),
                "stage-implementation"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_stage_conversation "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                Integer.class, source.getId().getValue(),
                "stage-requirement"));
    }

    @Test
    void should_RoundTripDynamicStageActiveRunAndWorkbenchWriteLease() {
        // Given
        Workbench source = dynamicWorkbench("workbench-dynamic-run");
        repository.add(source);
        Workbench bound = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        bound.bindStageConversation(
                "stage-implementation", "stage-session-0", OWNER,
                bound.getVersion(), NOW.plusSeconds(1));
        repository.update(bound);
        Workbench prepared = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);

        // When
        prepared.prepareStageRun(
                "stage-implementation", "stage-run-1",
                RunMode.MODIFY_WORKSPACE, OWNER, prepared.getVersion(),
                NOW.plusSeconds(2));
        repository.update(prepared);
        Workbench restored = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);

        // Then
        assertEquals("stage-run-1", restored.stage("stage-implementation")
                .getActiveRunReference().getRunIdentifier());
        assertEquals(RunMode.MODIFY_WORKSPACE,
                restored.stage("stage-implementation")
                        .getActiveRunReference().getRunMode());
        assertEquals(restored.stage("stage-implementation")
                        .getActiveRunReference(),
                restored.getActiveWriteRunReference());
        assertEquals("stage-run-1", jdbc.queryForObject(
                "SELECT active_write_run_id FROM workbench WHERE id=?",
                String.class, source.getId().getValue()));
        assertEquals("MODIFY_WORKSPACE", jdbc.queryForObject(
                "SELECT active_run_mode FROM workbench_stage "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                String.class, source.getId().getValue(),
                "stage-implementation"));

        // When
        restored.finishStageRun(
                "stage-implementation", "stage-run-1",
                NOW.plusSeconds(3));
        repository.update(restored);
        Workbench finished = repository.findById(source.getId())
                .orElseThrow(AssertionError::new);

        // Then
        assertNull(finished.stage("stage-implementation")
                .getActiveRunReference());
        assertNull(finished.getActiveWriteRunReference());
        assertNull(jdbc.queryForObject(
                "SELECT active_write_run_id FROM workbench WHERE id=?",
                String.class, source.getId().getValue()));
    }

    @Test
    void should_FailClosed_When_DynamicStageSnapshotJsonOrHashIsCorrupted() {
        // Given
        Workbench hashCorrupted = dynamicWorkbench("workbench-stage-hash-corrupted");
        Workbench jsonCorrupted = dynamicWorkbench("workbench-stage-json-corrupted");
        repository.add(hashCorrupted);
        repository.add(jsonCorrupted);

        // When
        jdbc.update("UPDATE workbench_stage SET stage_snapshot_hash=? "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                WorkbenchPersistenceFixtures.HASH_F,
                hashCorrupted.getId().getValue(), "stage-requirement");
        jdbc.update("UPDATE workbench_stage SET stage_snapshot_json=? "
                        + "WHERE workbench_id=? AND stage_instance_identifier=?",
                "{}", jsonCorrupted.getId().getValue(), "stage-implementation");

        // Then
        assertThrows(IllegalStateException.class,
                () -> repository.findById(hashCorrupted.getId()));
        assertThrows(IllegalStateException.class,
                () -> repository.findById(jsonCorrupted.getId()));
    }

    private Workbench dynamicWorkbench(String id) {
        WorkbenchStageSnapshot requirement = stageSnapshot(
                "requirement-analysis", 10, "需求分析");
        WorkbenchStageSnapshot implementation = stageSnapshot(
                "implementation", 30, "开发测试",
                Set.of(RunMode.DISCUSS_READ_ONLY,
                        RunMode.MODIFY_WORKSPACE));
        return Workbench.create(
                WorkbenchId.of(id), OWNER, "Dynamic Workbench", "实现动态阶段",
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
        return stageSnapshot(definitionIdentifier, sequenceNumber,
                displayName, Set.of(RunMode.DISCUSS_READ_ONLY));
    }

    private WorkbenchStageSnapshot stageSnapshot(
            String definitionIdentifier, int sequenceNumber,
            String displayName, Set<RunMode> allowedRunModes) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(definitionIdentifier, WorkbenchStageDraftContent.create(
                        sequenceNumber, displayName, "阶段说明", "阶段规则",
                        allowedRunModes,
                        List.of(new StageCommandSelection(
                                "architecture-review", "1.0.0")),
                        List.of(new StageSkillSelection(
                                "domain-modeling-audit", "1.0.0", false)),
                        List.of(new StageMcpServerSelection(
                                "repository-query", "1.0.0", true))),
                administrator, NOW.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                definitionIdentifier, catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        List.of(new StageCommandReference(
                                "architecture-review", "1.0.0",
                                WorkbenchPersistenceFixtures.HASH_A)),
                        List.of(new StageSkillReference(
                                "domain-modeling-audit", "1.0.0",
                                WorkbenchPersistenceFixtures.HASH_B, false)),
                        List.of(new StageMcpServerReference(
                                "repository-query", "1.0.0",
                                WorkbenchPersistenceFixtures.HASH_C, true,
                                CapabilityAccess.READ, "STDIO"))),
                administrator, NOW.minusSeconds(1));
        return WorkbenchStageSnapshot.fromPublishedRevision(revision);
    }

    private void assertStage(
            WorkbenchStageState expected, WorkbenchStageState actual) {
        assertEquals(expected.getStageInstanceIdentifier(),
                actual.getStageInstanceIdentifier());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getConversationGeneration(),
                actual.getConversationGeneration());
        assertEquals(expected.getActiveRunIdentifier(),
                actual.getActiveRunIdentifier());
        assertEquals(expected.getActiveRunMode(), actual.getActiveRunMode());
        assertEquals(expected.getActiveRunReference(),
                actual.getActiveRunReference());
        assertEquals(expected.getActiveRunPreparedAt(),
                actual.getActiveRunPreparedAt());
        assertEquals(expected.getLastActivityAt(), actual.getLastActivityAt());
        assertEquals(expected.getCompletedAt(), actual.getCompletedAt());
        assertSnapshot(expected.getSnapshot(), actual.getSnapshot());
    }

    private void assertSnapshot(
            WorkbenchStageSnapshot expected, WorkbenchStageSnapshot actual) {
        assertEquals(expected.getDefinitionIdentifier(),
                actual.getDefinitionIdentifier());
        assertEquals(expected.getDefinitionRevision(), actual.getDefinitionRevision());
        assertEquals(expected.getDefinitionHash(), actual.getDefinitionHash());
        assertEquals(expected.getSequenceNumber(), actual.getSequenceNumber());
        assertEquals(expected.getDisplayName(), actual.getDisplayName());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(expected.getStageRules(), actual.getStageRules());
        assertEquals(expected.getAllowedRunModes(), actual.getAllowedRunModes());
        assertEquals(expected.getCommandReferences(), actual.getCommandReferences());
        assertEquals(expected.getSkillReferences(), actual.getSkillReferences());
        assertEquals(expected.getMcpServerReferences(), actual.getMcpServerReferences());
        assertEquals(expected.getSnapshotHash(), actual.getSnapshotHash());
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
        assertEquals(expected.getActiveWriteRunReference(),
                actual.getActiveWriteRunReference());
        assertEquals(expected.getStages().size(), actual.getStages().size());
        for (WorkbenchStageState expectedStage : expected.getStages()) {
            assertStage(expectedStage, actual.stage(
                    expectedStage.getStageInstanceIdentifier()));
        }
    }
}
