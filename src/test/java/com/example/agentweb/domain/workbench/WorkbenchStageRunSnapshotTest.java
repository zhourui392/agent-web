package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 动态 Stage Run 不可变执行快照及终态绑定测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageRunSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");

    @Test
    void should_FreezeDynamicStageIdentityContextAndExecutionFacts() {
        // Given
        RepositoryScope scope = WorkbenchDomainFixtures.repositoryScope();
        WorkbenchStageSnapshot stageSnapshot = stageSnapshot();

        // When
        WorkbenchStageRunSnapshot snapshot = WorkbenchStageRunSnapshot.create(
                "run-stage-1", WORKBENCH_ID, "stage-design", stageSnapshot,
                "submit-stage-1", repeat('1'), RunMode.DISCUSS_READ_ONLY,
                scope, WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-stage-1", repeat('2')),
                capabilityBinding(), null, 0L, repeat('3'),
                Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'), RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(),
                        "agent-web", 1800L, 8388608L),
                Collections.emptyList(),
                Collections.singletonList(verifiedStageUpload()), NOW);

        // Then
        assertEquals("stage-design", snapshot.getStageInstanceIdentifier());
        assertEquals("solution-design",
                snapshot.getStageDefinitionIdentifier());
        assertEquals(stageSnapshot.getDefinitionRevision(),
                snapshot.getStageDefinitionRevision());
        assertEquals(stageSnapshot.getSnapshotHash(),
                snapshot.getStageSnapshotHash());
        assertNull(snapshot.getCommandBinding());
        assertEquals(0L, snapshot.getContextVersion());
        assertEquals(repeat('3'), snapshot.getContextHash());
        assertEquals("workbench-1:stage-design",
                snapshot.executionOriginReference());
        WorkbenchStageUploadedAttachmentBinding uploadBinding = snapshot
                .getVerifiedUploadedAttachments().get(0).getBinding();
        assertEquals(OWNER, uploadBinding.getOwner());
        assertEquals(WORKBENCH_ID, uploadBinding.getWorkbenchId());
        assertEquals("stage-design",
                uploadBinding.getStageInstanceIdentifier());
        assertEquals("stage-session", uploadBinding.getConversationId());
        assertEquals(0, uploadBinding.getConversationGeneration());
        assertEquals("run-stage-1", snapshot.requireReplay(
                WORKBENCH_ID, "stage-design", "submit-stage-1",
                repeat('1')));
    }

    @Test
    void should_RejectUploadedAttachmentBoundToAnotherDynamicStage() {
        // Given
        RepositoryScope scope = WorkbenchDomainFixtures.repositoryScope();
        VerifiedWorkbenchStageUploadedConversationAttachment foreignUpload =
                VerifiedWorkbenchStageUploadedConversationAttachment.restore(
                        "stage-upload-foreign",
                        new WorkbenchStageUploadedAttachmentBinding(
                                OWNER, WORKBENCH_ID, "stage-implement",
                                "stage-session", 0),
                        "implementation.md", "text/markdown", 64L,
                        repeat('8'), repeat('9'),
                        "attachment-implementation.md",
                        NOW.plusSeconds(3600), 0L);

        // When / Then
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> WorkbenchStageRunSnapshot.create(
                        "run-stage-foreign", WORKBENCH_ID, "stage-design",
                        stageSnapshot(), "submit-stage-foreign", repeat('1'),
                        RunMode.DISCUSS_READ_ONLY, scope,
                        WorkbenchDomainFixtures.snapshotReference(
                                "snapshot-stage-foreign", repeat('2')),
                        capabilityBinding(), null, 0L, repeat('3'),
                        Collections.emptyList(),
                        Collections.singletonList(PromptPartSnapshot.of(
                                "USER_INPUT", "user", repeat('4'), 32)),
                        repeat('5'), RuntimeEnforcementSnapshot.readOnly(
                                "CODEX", "0.42", scope.getScopeHash(),
                                "agent-web", 1800L, 8388608L),
                        Collections.emptyList(), List.of(foreignUpload), NOW));
        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
    }

    @Test
    void should_StrictlyFinishOnlyExactDynamicStageRun() {
        // Given
        Workbench workbench = preparedWorkbench("run-stage-1");
        WorkbenchStageRunSnapshot snapshot = snapshot("run-stage-1");

        // When
        snapshot.finishRequiredRun(
                workbench, "run-stage-1", NOW.plusSeconds(3));

        // Then
        assertNull(workbench.stage("stage-design").getActiveRunReference());
        assertEquals(3L, workbench.getVersion());

        Workbench another = preparedWorkbench("another-run");
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> snapshot.finishRequiredRun(
                        another, "run-stage-1", NOW.plusSeconds(4)));
        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
        assertEquals("another-run", another.stage("stage-design")
                .getActiveRunReference().getRunIdentifier());
    }

    @Test
    void should_RequireExactWorkbenchStageChatRunOrigin() {
        // Given
        Workbench workbench = preparedWorkbench("run-stage-1");
        WorkbenchStageRunSnapshot snapshot = snapshot("run-stage-1");
        ChatRun exact = ChatRun.submit(
                ChatRunId.of("run-stage-1"), "stage-session", 1L,
                "submit-stage-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:stage-design", "run-stage-1"), NOW);

        // When / Then
        snapshot.requireExactRun(workbench, exact, "run-stage-1");
        assertThrows(RuntimeException.class,
                () -> snapshot.requireExactRun(
                        workbench,
                        ChatRun.submit(
                                ChatRunId.of("run-stage-1"), "stage-session", 1L,
                                "submit-stage-1", false, RunOrigin.WORKBENCH,
                                ExecutionContextReference.of(
                                        "workbench-1:another-stage",
                                        "run-stage-1"), NOW),
                        "run-stage-1"));
    }

    private WorkbenchStageRunSnapshot snapshot(String runIdentifier) {
        RepositoryScope scope = WorkbenchDomainFixtures.repositoryScope();
        return WorkbenchStageRunSnapshot.create(
                runIdentifier, WORKBENCH_ID, "stage-design", stageSnapshot(),
                "submit-stage-1", repeat('1'), RunMode.DISCUSS_READ_ONLY,
                scope, WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-stage-1", repeat('2')),
                capabilityBinding(), null, 0L, repeat('3'),
                Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'), RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(),
                        "agent-web", 1800L, 8388608L),
                Collections.emptyList(), Collections.emptyList(),
                NOW.plusSeconds(2));
    }

    private Workbench preparedWorkbench(String runIdentifier) {
        Workbench workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Dynamic Workbench", "Goal",
                AgentType.CODEX, "local",
                WorkbenchDomainFixtures.repositoryScope(),
                WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-stage-1", repeat('2')),
                Collections.singletonList(WorkbenchStageState.initial(
                        "stage-design", stageSnapshot())), NOW);
        workbench.bindStageConversation(
                "stage-design", "stage-session", OWNER,
                0L, NOW.plusSeconds(1));
        workbench.prepareStageRun(
                "stage-design", runIdentifier,
                RunMode.DISCUSS_READ_ONLY, OWNER,
                1L, NOW.plusSeconds(2));
        return workbench;
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                StageCatalogEditor.create("admin-1", "Admin"), NOW);
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        StageCatalogEditor.create("admin-1", "Admin"),
                        NOW.plusSeconds(1)));
    }

    private ResolvedCapabilityBinding capabilityBinding() {
        return ResolvedCapabilityBinding.resolve(
                "stage-policy-1", "solution-design", "1", repeat('6'),
                Collections.singletonList(new ResolvedRuleBinding(
                        "stage/solution-design", "1", "stage-snapshot",
                        repeat('7'), true, "Stage rules")),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "codex-compatible");
    }

    private VerifiedWorkbenchStageUploadedConversationAttachment
            verifiedStageUpload() {
        return VerifiedWorkbenchStageUploadedConversationAttachment.restore(
                "stage-upload-1",
                new WorkbenchStageUploadedAttachmentBinding(
                        OWNER, WORKBENCH_ID, "stage-design",
                        "stage-session", 0),
                "design.md", "text/markdown", 64L,
                repeat('8'), repeat('9'), "attachment-design.md",
                NOW.plusSeconds(3600), 0L);
    }

    private String repeat(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
