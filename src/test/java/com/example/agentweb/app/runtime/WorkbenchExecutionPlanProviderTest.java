package com.example.agentweb.app.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.RuntimeAttachmentExpectation;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfileCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Workbench 冻结 Snapshot 到公共 Runtime Plan 的应用编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchExecutionPlanProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final String RUN_ID = "workbench-run-1";
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final String STAGE_IDENTIFIER = "stage-design";

    private WorkbenchStageRunSnapshotRepository snapshotRepository;
    private WorkbenchStageRunPromptPayloadRepository promptRepository;
    private WorkbenchRepository workbenchRepository;
    private WorkbenchExecutionPlanProvider provider;
    private RepositoryScope scope;
    private Workbench workbench;
    private WorkbenchRunPromptPayload promptPayload;
    private WorkbenchStageRunSnapshot snapshot;
    private ChatRun run;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(
                WorkbenchStageRunSnapshotRepository.class);
        promptRepository = mock(
                WorkbenchStageRunPromptPayloadRepository.class);
        workbenchRepository = mock(WorkbenchRepository.class);
        provider = new WorkbenchExecutionPlanProvider(
                snapshotRepository, promptRepository, workbenchRepository);

        scope = scope();
        WorkbenchStageSnapshot frozenStage = stageSnapshot();
        workbench = Workbench.create(
                WORKBENCH_ID, OwnerReference.of("owner-1", "Alex"),
                "Workbench", "Implement the approved solution",
                AgentType.CODEX, "local", scope, snapshotReference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_IDENTIFIER, frozenStage)), NOW);
        workbench.bindStageConversation(
                STAGE_IDENTIFIER, "stage-session-1",
                OwnerReference.of("owner-1", "Alex"),
                0L, NOW.plusSeconds(1));
        workbench.prepareStageRun(
                STAGE_IDENTIFIER, RUN_ID, RunMode.MODIFY_WORKSPACE,
                OwnerReference.of("owner-1", "Alex"),
                1L, NOW.plusSeconds(2));
        promptPayload = WorkbenchRunPromptPayload.freeze(
                RUN_ID, "frozen workbench prompt",
                WorkbenchPromptHistoryDelivery.TYPED, NOW.plusSeconds(2));
        snapshot = snapshot(
                frozenStage, Collections.emptyList(), Collections.emptyList());
        run = ChatRun.submit(
                ChatRunId.of(RUN_ID), "stage-session-1", 21L,
                "stage-submit-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:" + STAGE_IDENTIFIER, RUN_ID),
                NOW.plusSeconds(2));
    }

    @Test
    void should_SupportOnlyWorkbenchOrigin_When_SelectingProvider() {
        assertTrue(provider.supports(RunOrigin.WORKBENCH));
        assertEquals(false, provider.supports(RunOrigin.CHAT));
    }

    @Test
    void should_AssembleStageSnapshotPromptScopeAndRuntimeFacts_When_Persisted() {
        // Given
        persistAll();

        // When
        AgentExecutionPlan plan = provider.prepare(run);

        // Then
        assertEquals(RUN_ID, plan.getExecutionIdentity().getExecutionId());
        assertEquals("owner-1", plan.getExecutionIdentity().getOwnerId());
        assertEquals("workbench-1:" + STAGE_IDENTIFIER,
                plan.getExecutionIdentity().getOriginReference());
        assertEquals(AgentType.CODEX,
                plan.getRuntimeSelection().getAgentType());
        assertEquals(RuntimeVersionPolicy.Mode.EXACT,
                plan.getRuntimeSelection().getRuntimeVersionPolicy().getMode());
        assertEquals("0.145.0", plan.getRuntimeSelection()
                .getRuntimeVersionPolicy().exactVersion().orElseThrow());
        assertEquals(promptPayload.getFinalPrompt(),
                plan.getPromptPayload().getFinalPrompt());
        assertEquals(promptPayload.getPromptHash(),
                plan.getPromptPayload().getPromptHash());
        assertEquals(HistoryDelivery.TYPED,
                plan.getPromptPayload().getHistoryDelivery());
        assertEquals(Arrays.asList(
                        "/workspace/service-a", "/workspace/service-b"),
                plan.getWorkspaceLayout().getWritableRoots());
        assertEquals(SandboxMode.WORKSPACE_WRITE,
                plan.getWorkspaceLayout().getSandboxMode());
        assertSame(snapshot.getCapabilityBinding(),
                plan.getCapabilityBinding());
    }

    @Test
    void should_KeepCodexCompatibility_When_ProfileFileHasNoProfiles() {
        // Given
        provider = new WorkbenchExecutionPlanProvider(
                snapshotRepository, promptRepository, workbenchRepository,
                new AgentRuntimeProfileCatalog(Collections.emptyList()), null);
        persistAll();

        // When
        AgentExecutionPlan plan = provider.prepare(run);

        // Then
        assertEquals(AgentType.CODEX, plan.getRuntimeSelection().getAgentType());
        assertEquals(RuntimeVersionPolicy.Mode.EXACT,
                plan.getRuntimeSelection().getRuntimeVersionPolicy().getMode());
        assertEquals("0.145.0", plan.getRuntimeSelection()
                .getRuntimeVersionPolicy().exactVersion().orElseThrow());
    }

    @Test
    void should_ProjectStageAttachments_When_PreparingRuntimePlan() {
        // Given
        DocumentReference reference = DocumentReference.of(
                "service-b", "docs/design.md");
        VerifiedWorkbenchRunAttachment repositoryAttachment =
                VerifiedWorkbenchRunAttachment.verify(
                        reference, repeat('9'), reference, repeat('9'),
                        "text/markdown", 128L, false);
        VerifiedWorkbenchStageUploadedConversationAttachment uploaded =
                VerifiedWorkbenchStageUploadedConversationAttachment.restore(
                        "stage-attachment-1",
                        new WorkbenchStageUploadedAttachmentBinding(
                                OwnerReference.of("owner-1", "Alex"),
                                WORKBENCH_ID, STAGE_IDENTIFIER,
                                "stage-session-1", 0),
                        "stage-design.md", "text/markdown", 64L,
                        repeat('7'), repeat('8'),
                        "attachment-stage-design.md",
                        NOW.plusSeconds(3600), 0L);
        snapshot = snapshot(
                stageSnapshot(),
                Collections.singletonList(repositoryAttachment),
                Collections.singletonList(uploaded));
        persistAll();

        // When
        AgentExecutionPlan plan = provider.prepare(run);

        // Then
        assertEquals(2, plan.getAttachmentExpectations().size());
        RuntimeAttachmentExpectation repository =
                plan.getAttachmentExpectations().get(0);
        assertEquals("service-b", repository.getRepositoryKey());
        assertEquals("docs/design.md", repository.getRelativePath());
        RuntimeAttachmentExpectation browser =
                plan.getAttachmentExpectations().get(1);
        assertEquals(RuntimeAttachmentExpectation.Type.UPLOADED_CONVERSATION,
                browser.getType());
        assertEquals("stage-attachment-1", browser.getAttachmentId());
        assertEquals("attachment-stage-design.md",
                browser.getRuntimeFileName());
    }

    @Test
    void should_FailClosed_When_StageSnapshotPromptOrWorkbenchIsMissing() {
        when(snapshotRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));

        when(snapshotRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(snapshot));
        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));

        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(promptPayload));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));
    }

    @Test
    void should_RejectMismatchedPrivatePrompt_When_HashBindingDiffers() {
        when(snapshotRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(snapshot));
        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(WorkbenchRunPromptPayload.freeze(
                        "another-run", "frozen workbench prompt",
                        WorkbenchPromptHistoryDelivery.TYPED,
                        NOW.plusSeconds(2))));

        assertThrows(RuntimeException.class,
                () -> provider.prepare(run));
    }

    @Test
    void should_RejectMismatchedStageOrigin_When_ChatRunBelongsElsewhere() {
        persistAll();
        ChatRun mismatched = ChatRun.submit(
                ChatRunId.of(RUN_ID), "stage-session-1", 21L,
                "stage-submit-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:another-stage", RUN_ID),
                NOW.plusSeconds(2));

        assertThrows(RuntimeException.class,
                () -> provider.prepare(mismatched));
    }

    @Test
    void should_SubstituteOnlyPrimaryRootByKey_When_PrimaryNotFirstAndWorktreeEnabled() {
        // primary = "z-service" (alphabetically AFTER "a-library")
        // RepositoryScope sorts by key: [a-library, z-service]
        // Without key-based substitution, [0] would hit a-library -- wrong.
        String libraryRoot = "/workspace/a-library";
        String primaryRoot = "/workspace/z-service";
        String worktreePath = "/workspace/.worktrees/wb/workbench-wt";

        RepositoryScope wtScope = RepositoryScope.create(
                "/workspace",
                RepositorySelection.of(
                        "z-service", Arrays.asList("a-library", "z-service")),
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "a-library", libraryRoot,
                                repeat('c'), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "z-service", primaryRoot,
                                repeat('d'), false)),
                8);

        WorkspaceTopology wtTopology = WorkspaceTopology.of(
                "/workspace", RepositorySelection.of(
                        "z-service", Arrays.asList("a-library", "z-service")));
        WorkspaceSnapshotReference wtSnapshotRef = new WorkspaceSnapshotReference(
                "workspace-snapshot-wt", wtTopology.getTopologyHash(),
                repeat('e'), 2);

        Workbench wtWorkbench = Workbench.createWithWorktree(
                WORKBENCH_ID, OwnerReference.of("owner-1", "Alex"),
                "Workbench WT", "Implement with worktree isolation",
                AgentType.CODEX, "local", wtScope, wtSnapshotRef,
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_IDENTIFIER, stageSnapshot())), NOW,
                worktreePath, "wb/workbench-wt");
        wtWorkbench.bindStageConversation(
                STAGE_IDENTIFIER, "stage-session-wt",
                OwnerReference.of("owner-1", "Alex"),
                0L, NOW.plusSeconds(1));
        wtWorkbench.prepareStageRun(
                STAGE_IDENTIFIER, RUN_ID, RunMode.MODIFY_WORKSPACE,
                OwnerReference.of("owner-1", "Alex"),
                1L, NOW.plusSeconds(2));

        WorkbenchStageRunSnapshot wtSnapshot = WorkbenchStageRunSnapshot.create(
                RUN_ID, WORKBENCH_ID, STAGE_IDENTIFIER, stageSnapshot(),
                "stage-submit-wt", repeat('1'),
                RunMode.MODIFY_WORKSPACE, wtScope, wtSnapshotRef,
                binding(stageSnapshot()), null, 0L, repeat('2'),
                Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner", repeat('3'), 23)),
                promptPayload.getPromptHash(),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.145.0", wtScope.getScopeHash(),
                        "z-service", Arrays.asList("a-library", "z-service"),
                        1800L, 8_388_608L),
                Collections.emptyList(), Collections.emptyList(),
                NOW.plusSeconds(2));

        when(snapshotRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(wtSnapshot));
        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(promptPayload));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(wtWorkbench));

        AgentExecutionPlan plan = provider.prepare(run);
        WorkspaceLayout layout = plan.getWorkspaceLayout();

        // primary root must be worktree path (not original z-service root)
        assertEquals(worktreePath, layout.getPrimaryRepositoryRoot());
        // worktree path in readable + writable roots
        assertTrue(layout.getReadableRoots().contains(worktreePath));
        assertTrue(layout.getWritableRoots().contains(worktreePath));
        // a-library root unchanged
        assertTrue(layout.getReadableRoots().contains(libraryRoot));
        assertTrue(layout.getWritableRoots().contains(libraryRoot));
        // original primary root must NOT appear (replaced)
        assertFalse(layout.getReadableRoots().contains(primaryRoot));
        assertFalse(layout.getWritableRoots().contains(primaryRoot));
    }

    private WorkbenchStageRunSnapshot snapshot(
            WorkbenchStageSnapshot frozenStage,
            java.util.List<VerifiedWorkbenchRunAttachment> repositoryAttachments,
            java.util.List<VerifiedWorkbenchStageUploadedConversationAttachment>
                    uploadedAttachments) {
        return WorkbenchStageRunSnapshot.create(
                RUN_ID, WORKBENCH_ID, STAGE_IDENTIFIER, frozenStage,
                "stage-submit-1", repeat('1'),
                RunMode.MODIFY_WORKSPACE, scope, snapshotReference(),
                binding(frozenStage), null, 0L, repeat('2'),
                Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner", repeat('3'), 23)),
                promptPayload.getPromptHash(),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.145.0", scope.getScopeHash(),
                        "service-a", Arrays.asList("service-a", "service-b"),
                        1800L, 8_388_608L),
                repositoryAttachments, uploadedAttachments,
                NOW.plusSeconds(2));
    }

    private void persistAll() {
        when(snapshotRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(snapshot));
        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(promptPayload));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
    }

    private RepositoryScope scope() {
        return RepositoryScope.create(
                "/workspace",
                RepositorySelection.of(
                        "service-a", Arrays.asList("service-a", "service-b")),
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "service-b", "/workspace/service-b",
                                repeat('b'), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "service-a", "/workspace/service-a",
                                repeat('a'), false)),
                8);
    }

    private WorkspaceSnapshotReference snapshotReference() {
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", RepositorySelection.of(
                        "service-a", Arrays.asList("service-a", "service-b")));
        return new WorkspaceSnapshotReference(
                "workspace-snapshot-1", topology.getTopologyHash(),
                repeat('d'), 2);
    }

    private ResolvedCapabilityBinding binding(
            WorkbenchStageSnapshot frozenStage) {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "solution-design", "1",
                frozenStage.getSnapshotHash(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                "common-runtime@1");
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "完成完整方案", "Stage rules",
                        Set.of(RunMode.MODIFY_WORKSPACE),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator, NOW.minusSeconds(1)));
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }
}
