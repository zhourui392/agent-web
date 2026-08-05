package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageRunReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 Stage Snapshot、人工状态与独立会话代际不变量测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchDynamicStageTest {

    private static final Instant NOW = Instant.parse("2026-08-05T08:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final StageCatalogEditor ADMINISTRATOR =
            StageCatalogEditor.create("admin-1", "Admin");

    @Test
    void should_CreateWorkbenchWithImmutableStagesInPublishedSequence() {
        // Given
        WorkbenchStageSnapshot requirement = snapshot(
                "requirement-analysis", 10, "需求分析");
        WorkbenchStageSnapshot implementation = snapshot(
                "implementation", 30, "开发测试");

        // When
        Workbench workbench = Workbench.create(
                WorkbenchId.of("workbench-1"), OWNER, "动态 Workbench", "目标",
                AgentType.CODEX, "local", WorkbenchDomainFixtures.repositoryScope(),
                WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-1", WorkbenchDomainFixtures.repeat('a')),
                Arrays.asList(
                        WorkbenchStageState.initial("stage-implementation", implementation),
                        WorkbenchStageState.initial("stage-requirement", requirement)),
                NOW);

        // Then
        assertEquals(List.of("requirement-analysis", "implementation"),
                workbench.getStages().stream()
                        .map(stage -> stage.getSnapshot().getDefinitionIdentifier())
                        .toList());
        assertEquals("stage-requirement",
                workbench.getStages().get(0).getStageInstanceIdentifier());
        assertNotEquals(requirement.getDefinitionHash(), requirement.getSnapshotHash());
    }

    @Test
    void should_RejectDuplicateDefinitionSequenceOrInstanceIdentifier() {
        // Given
        WorkbenchStageSnapshot requirement = snapshot(
                "requirement-analysis", 10, "需求分析");
        WorkbenchStageSnapshot sameSequence = snapshot(
                "implementation", 10, "开发测试");

        // When / Then
        assertThrows(IllegalArgumentException.class, () -> dynamicWorkbench(Arrays.asList(
                WorkbenchStageState.initial("stage-1", requirement),
                WorkbenchStageState.initial("stage-2", requirement))));
        assertThrows(IllegalArgumentException.class, () -> dynamicWorkbench(Arrays.asList(
                WorkbenchStageState.initial("stage-1", requirement),
                WorkbenchStageState.initial("stage-2", sameSequence))));
        assertThrows(IllegalArgumentException.class, () -> dynamicWorkbench(Arrays.asList(
                WorkbenchStageState.initial("stage-1", requirement),
                WorkbenchStageState.initial("stage-1", snapshot(
                        "implementation", 30, "开发测试")))));
        assertThrows(IllegalArgumentException.class,
                () -> dynamicWorkbench(Collections.emptyList()));
    }

    @Test
    void should_FailClosedWhenRestoredStageSnapshotHashDoesNotMatch() {
        // Given
        WorkbenchStageDefinitionRevision revision = revision(
                "requirement-analysis", 10, "需求分析");
        WorkbenchStageSnapshot snapshot =
                WorkbenchStageSnapshot.fromPublishedRevision(revision);

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> WorkbenchStageSnapshot.restore(
                        revision.getDefinitionIdentifier(), revision.getRevisionNumber(),
                        revision.getDefinitionHash(), revision.getSequenceNumber(),
                        revision.getDisplayName(), revision.getDescription(),
                        revision.getStageRules(), revision.getAllowedRunModes(),
                        revision.getCommandReferences(), revision.getSkillReferences(),
                        revision.getMcpServerReferences(),
                        WorkbenchDomainFixtures.repeat('f')));
        assertEquals(snapshot.getSnapshotHash(),
                WorkbenchStageSnapshot.restore(
                        revision.getDefinitionIdentifier(), revision.getRevisionNumber(),
                        revision.getDefinitionHash(), revision.getSequenceNumber(),
                        revision.getDisplayName(), revision.getDescription(),
                        revision.getStageRules(), revision.getAllowedRunModes(),
                        revision.getCommandReferences(), revision.getSkillReferences(),
                        revision.getMcpServerReferences(), snapshot.getSnapshotHash())
                        .getSnapshotHash());
    }

    @Test
    void should_CompleteAndReopenAnyDynamicStage_WithoutNeighborGate() {
        // Given
        Workbench workbench = dynamicWorkbench(Arrays.asList(
                WorkbenchStageState.initial("stage-requirement", snapshot(
                        "requirement-analysis", 10, "需求分析")),
                WorkbenchStageState.initial("stage-implementation", snapshot(
                        "implementation", 30, "开发测试"))));

        // When
        boolean completed = workbench.completeStage(
                "stage-implementation", OWNER, 0L, NOW.plusSeconds(1));
        boolean reopened = workbench.reopenStage(
                "stage-implementation", OWNER, 1L, NOW.plusSeconds(2));

        // Then
        assertTrue(completed);
        assertTrue(reopened);
        assertEquals(WorkbenchStageStatus.NOT_STARTED,
                workbench.stage("stage-implementation").getStatus());
        assertEquals(WorkbenchStageStatus.NOT_STARTED,
                workbench.stage("stage-requirement").getStatus());
        assertEquals(2L, workbench.getVersion());
    }

    @Test
    void should_BindAndRestartIndependentStageConversationGenerations() {
        // Given
        Workbench workbench = dynamicWorkbench(Arrays.asList(
                WorkbenchStageState.initial("stage-requirement", snapshot(
                        "requirement-analysis", 10, "需求分析")),
                WorkbenchStageState.initial("stage-implementation", snapshot(
                        "implementation", 30, "开发测试"))));

        // When
        workbench.bindStageConversation(
                "stage-implementation", "stage-session-0", OWNER,
                0L, NOW.plusSeconds(1));
        workbench.completeStage(
                "stage-implementation", OWNER, 1L, NOW.plusSeconds(2));
        workbench.reopenStage(
                "stage-implementation", OWNER, 2L, NOW.plusSeconds(3));
        workbench.restartStageConversation(
                "stage-implementation", "stage-session-1", OWNER,
                3L, NOW.plusSeconds(4));

        // Then
        WorkbenchStageState implementation = workbench.stage(
                "stage-implementation");
        assertEquals(WorkbenchStageStatus.IN_PROGRESS,
                implementation.getStatus());
        assertEquals(1, implementation.getConversationGeneration());
        assertEquals(2, implementation.getConversationHistory().size());
        assertEquals("stage-session-1",
                implementation.currentConversation().getConversationId());
        assertEquals(NOW.plusSeconds(4), implementation
                .getConversationHistory().get(0).getRetiredAt());
        assertTrue(workbench.stage("stage-requirement")
                .getConversationHistory().isEmpty());
        assertNull(workbench.stage("stage-requirement").currentConversation());
        assertEquals(4L, workbench.getVersion());
    }

    @Test
    void should_RejectStageConversationRestartUntilStageIsInProgress() {
        // Given
        Workbench workbench = dynamicWorkbench(Collections.singletonList(
                WorkbenchStageState.initial("stage-requirement", snapshot(
                        "requirement-analysis", 10, "需求分析"))));
        workbench.bindStageConversation(
                "stage-requirement", "stage-session-0", OWNER,
                0L, NOW.plusSeconds(1));

        // When
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.restartStageConversation(
                        "stage-requirement", "stage-session-1", OWNER,
                        1L, NOW.plusSeconds(2)));

        // Then
        assertEquals(WorkbenchErrorCode.STAGE_RESTART_INVALID,
                failure.getCode());
        assertEquals("stage-session-0", workbench.stage("stage-requirement")
                .currentConversation().getConversationId());
    }

    @Test
    void should_PrepareAndFinishDynamicStageRun_WithSingleWriteLease() {
        // Given
        Workbench workbench = dynamicWorkbench(Arrays.asList(
                WorkbenchStageState.initial("stage-analysis", snapshot(
                        "analysis", 10, "分析",
                        Set.of(RunMode.DISCUSS_READ_ONLY,
                                RunMode.MODIFY_WORKSPACE))),
                WorkbenchStageState.initial("stage-implementation", snapshot(
                        "implementation", 20, "实现",
                        Set.of(RunMode.MODIFY_WORKSPACE)))));
        workbench.bindStageConversation(
                "stage-analysis", "analysis-session", OWNER,
                0L, NOW.plusSeconds(1));
        workbench.bindStageConversation(
                "stage-implementation", "implementation-session", OWNER,
                1L, NOW.plusSeconds(2));

        // When
        WorkbenchStageRunReference prepared = workbench.prepareStageRun(
                "stage-analysis", "run-analysis", RunMode.MODIFY_WORKSPACE,
                OWNER, 2L, NOW.plusSeconds(3));

        // Then
        assertEquals("run-analysis", prepared.getRunIdentifier());
        assertEquals(RunMode.MODIFY_WORKSPACE, prepared.getRunMode());
        assertEquals(WorkbenchStageStatus.IN_PROGRESS,
                workbench.stage("stage-analysis").getStatus());
        assertEquals(prepared, workbench.stage("stage-analysis")
                .getActiveRunReference());
        assertEquals(prepared, workbench.getActiveWriteRunReference());
        assertEquals(3L, workbench.getVersion());

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.prepareStageRun(
                        "stage-implementation", "run-implementation",
                        RunMode.MODIFY_WORKSPACE, OWNER,
                        3L, NOW.plusSeconds(4)));
        assertEquals(WorkbenchErrorCode.WRITE_RUN_ACTIVE,
                conflict.getCode());

        assertTrue(workbench.finishStageRun(
                "stage-analysis", "run-analysis", NOW.plusSeconds(5)));
        WorkbenchStageRunReference next = workbench.prepareStageRun(
                "stage-implementation", "run-implementation",
                RunMode.MODIFY_WORKSPACE, OWNER,
                4L, NOW.plusSeconds(6));
        assertEquals(next, workbench.getActiveWriteRunReference());
        assertEquals(5L, workbench.getVersion());
    }

    @Test
    void should_RejectUnallowedOrConcurrentRun_WithoutMutatingStage() {
        // Given
        Workbench workbench = dynamicWorkbench(Collections.singletonList(
                WorkbenchStageState.initial("stage-analysis", snapshot(
                        "analysis", 10, "分析"))));
        workbench.bindStageConversation(
                "stage-analysis", "analysis-session", OWNER,
                0L, NOW.plusSeconds(1));

        // When
        WorkbenchDomainException forbidden = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.prepareStageRun(
                        "stage-analysis", "run-modify",
                        RunMode.MODIFY_WORKSPACE, OWNER,
                        1L, NOW.plusSeconds(2)));

        // Then
        assertEquals(WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                forbidden.getCode());
        assertNull(workbench.stage("stage-analysis").getActiveRunReference());
        assertEquals(1L, workbench.getVersion());

        workbench.prepareStageRun(
                "stage-analysis", "run-read-only",
                RunMode.DISCUSS_READ_ONLY, OWNER,
                1L, NOW.plusSeconds(3));
        WorkbenchDomainException active = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.prepareStageRun(
                        "stage-analysis", "run-other",
                        RunMode.DISCUSS_READ_ONLY, OWNER,
                        2L, NOW.plusSeconds(4)));
        assertEquals(WorkbenchErrorCode.STAGE_RUN_ACTIVE,
                active.getCode());
        assertEquals("run-read-only", workbench.stage("stage-analysis")
                .getActiveRunReference().getRunIdentifier());
        assertEquals(2L, workbench.getVersion());
    }

    @Test
    void should_FinishRequiredDynamicStageRun_OnlyForExactActiveBinding() {
        // Given
        Workbench workbench = dynamicWorkbench(Collections.singletonList(
                WorkbenchStageState.initial("stage-analysis", snapshot(
                        "analysis", 10, "分析"))));
        workbench.bindStageConversation(
                "stage-analysis", "analysis-session", OWNER,
                0L, NOW.plusSeconds(1));
        workbench.prepareStageRun(
                "stage-analysis", "run-read-only",
                RunMode.DISCUSS_READ_ONLY, OWNER,
                1L, NOW.plusSeconds(2));

        // When
        WorkbenchDomainException mismatched = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.finishRequiredStageRun(
                        "stage-analysis", "another-run",
                        NOW.plusSeconds(3)));

        // Then
        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                mismatched.getCode());
        assertEquals("run-read-only", workbench.stage("stage-analysis")
                .getActiveRunReference().getRunIdentifier());
        assertEquals(2L, workbench.getVersion());

        workbench.finishRequiredStageRun(
                "stage-analysis", "run-read-only",
                NOW.plusSeconds(4));
        assertNull(workbench.stage("stage-analysis").getActiveRunReference());
        assertEquals(3L, workbench.getVersion());
    }

    @Test
    void should_PlanDynamicStageRunFromFrozenStageWithoutLegacyPhaseFacts() {
        // Given
        Workbench workbench = dynamicWorkbench(Collections.singletonList(
                WorkbenchStageState.initial("stage-design", snapshot(
                        "solution-design", 20, "方案设计",
                        Set.of(RunMode.DISCUSS_READ_ONLY,
                                RunMode.MODIFY_WORKSPACE)))));
        workbench.bindStageConversation(
                "stage-design", "design-session", OWNER,
                0L, NOW.plusSeconds(1));

        // When
        WorkbenchStageRunPreparationPlan plan =
                workbench.planStageRunPreparation(
                        "stage-design", RunMode.MODIFY_WORKSPACE,
                        OWNER, 1L);

        // Then
        assertEquals(workbench.getId(), plan.getWorkbenchId());
        assertEquals("stage-design", plan.getStageInstanceIdentifier());
        assertEquals("solution-design",
                plan.getStageSnapshot().getDefinitionIdentifier());
        assertEquals(RunMode.MODIFY_WORKSPACE, plan.getRunMode());
        assertEquals("design-session",
                plan.getConversation().requireCurrentConversationId());
        assertEquals(2, plan.getReadableRepositoryRoots().size());
        assertEquals(plan.getReadableRepositoryRoots(),
                plan.getWritableRepositoryRoots());
        assertEquals(List.of("agent-web", "shared-library"),
                plan.getWritableRepositoryKeys());
        assertEquals(1L, workbench.getVersion());
        assertNull(workbench.stage("stage-design").getActiveRunReference());
    }

    @Test
    void should_PlanUploadedAttachmentForExactDynamicStageConversation() {
        // Given
        Workbench workbench = dynamicWorkbench(Collections.singletonList(
                WorkbenchStageState.initial("stage-design", snapshot(
                        "solution-design", 20, "方案设计"))));
        workbench.bindStageConversation(
                "stage-design", "design-session", OWNER,
                0L, NOW.plusSeconds(1));

        // When
        WorkbenchStageUploadedAttachmentBinding binding =
                workbench.planStageUploadedAttachment(
                        "stage-design", 0, OWNER);

        // Then
        assertEquals(OWNER, binding.getOwner());
        assertEquals(workbench.getId(), binding.getWorkbenchId());
        assertEquals("stage-design", binding.getStageInstanceIdentifier());
        assertEquals("design-session", binding.getConversationId());
        assertEquals(0, binding.getConversationGeneration());

        WorkbenchDomainException stale = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.planStageUploadedAttachment(
                        "stage-design", 1, OWNER));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, stale.getCode());
    }

    private Workbench dynamicWorkbench(List<WorkbenchStageState> stages) {
        return Workbench.create(
                WorkbenchId.of("workbench-1"), OWNER, "动态 Workbench", "目标",
                AgentType.CODEX, "local", WorkbenchDomainFixtures.repositoryScope(),
                WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-1", WorkbenchDomainFixtures.repeat('a')),
                stages, NOW);
    }

    private WorkbenchStageSnapshot snapshot(
            String identifier, int sequenceNumber, String displayName) {
        return snapshot(identifier, sequenceNumber, displayName,
                Set.of(RunMode.DISCUSS_READ_ONLY));
    }

    private WorkbenchStageSnapshot snapshot(
            String identifier, int sequenceNumber, String displayName,
            Set<RunMode> allowedRunModes) {
        return WorkbenchStageSnapshot.fromPublishedRevision(
                revision(identifier, sequenceNumber, displayName,
                        allowedRunModes));
    }

    private WorkbenchStageDefinitionRevision revision(
            String identifier, int sequenceNumber, String displayName) {
        return revision(identifier, sequenceNumber, displayName,
                Set.of(RunMode.DISCUSS_READ_ONLY));
    }

    private WorkbenchStageDefinitionRevision revision(
            String identifier, int sequenceNumber, String displayName,
            Set<RunMode> allowedRunModes) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft(identifier, WorkbenchStageDraftContent.create(
                        sequenceNumber, displayName, "阶段说明", "阶段规则",
                        allowedRunModes, Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList()),
                ADMINISTRATOR, NOW);
        return catalog.publishDraft(
                identifier, catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                ADMINISTRATOR, NOW.plusSeconds(1));
    }
}
