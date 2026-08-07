package com.example.agentweb.app.workbench;

import com.example.agentweb.app.workbench.port.WorkbenchWorktreeGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage-only Workbench 人工生命周期的 Owner、版本与持久化编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchLifecycleAppServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-05T08:00:00Z");
    private static final Instant NOW =
            Instant.parse("2026-08-05T09:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference FOREIGN =
            OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-lifecycle-1");
    private static final String STAGE_IDENTIFIER = "stage-implementation";

    private WorkbenchRepository repository;
    private WorkbenchLifecycleAppService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkbenchRepository.class);
        WorkbenchWorktreeGateway worktreeGateway = mock(
                WorkbenchWorktreeGateway.class);
        service = new WorkbenchLifecycleAppService(
                repository, worktreeGateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void should_LoadOnlyOwnerWorkbench() {
        // Given
        Workbench workbench = newWorkbench();
        when(repository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));

        // When
        WorkbenchLifecycleResult result = service.load(OWNER, WORKBENCH_ID);

        // Then
        assertEquals(WORKBENCH_ID.getValue(), result.getWorkbenchId());
        assertEquals(WorkbenchStatus.ACTIVE, result.getStatus());
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.load(FOREIGN, WORKBENCH_ID));
        verify(repository, never()).update(workbench);
    }

    @Test
    void should_CompleteAndReopenStageAndPersistEachMutation() {
        // Given
        Workbench workbench = newWorkbench();
        when(repository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));

        // When
        WorkbenchStageLifecycleResult completed = service.completeStage(
                OWNER, WORKBENCH_ID, STAGE_IDENTIFIER, 0L);
        WorkbenchStageLifecycleResult reopened = service.reopenStage(
                OWNER, WORKBENCH_ID, STAGE_IDENTIFIER, 1L);

        // Then
        assertEquals(WorkbenchStageStatus.HUMAN_COMPLETED,
                completed.getStageStatus());
        assertEquals(WorkbenchStageStatus.NOT_STARTED,
                reopened.getStageStatus());
        assertEquals("implementation", reopened.getDefinitionIdentifier());
        assertEquals(2L, reopened.getWorkbenchVersion());
        verify(repository, times(2)).update(workbench);
    }

    @Test
    void should_RetainStageConversationWhenCompletingAndReopening() {
        // Given
        Workbench workbench = newWorkbench();
        workbench.bindStageConversation(
                STAGE_IDENTIFIER, "stage-session", OWNER,
                CREATED_AT.plusSeconds(1));
        when(repository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));

        // When
        WorkbenchStageLifecycleResult completed = service.completeStage(
                OWNER, WORKBENCH_ID, STAGE_IDENTIFIER, 1L);
        WorkbenchStageLifecycleResult reopened = service.reopenStage(
                OWNER, WORKBENCH_ID, STAGE_IDENTIFIER, 2L);

        // Then
        assertEquals("stage-session", completed.getConversationId());
        assertEquals("stage-session", reopened.getConversationId());
        assertEquals(WorkbenchStageStatus.IN_PROGRESS,
                reopened.getStageStatus());
        assertEquals(3L, reopened.getWorkbenchVersion());
        verify(repository, times(2)).update(workbench);
    }

    @Test
    void should_ArchiveOnceAndRejectStaleStageVersion() {
        // Given
        Workbench workbench = newWorkbench();
        when(repository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));

        // When
        WorkbenchDomainException stale = assertThrows(
                WorkbenchDomainException.class,
                () -> service.completeStage(
                        OWNER, WORKBENCH_ID, STAGE_IDENTIFIER, 1L));
        WorkbenchLifecycleResult archived = service.archive(
                OWNER, WORKBENCH_ID, 0L);
        WorkbenchLifecycleResult replayed = service.archive(
                OWNER, WORKBENCH_ID, 1L);

        // Then
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, stale.getCode());
        assertTrue(archived.isChanged());
        assertFalse(replayed.isChanged());
        assertEquals(WorkbenchStatus.ARCHIVED, replayed.getStatus());
        verify(repository, times(1)).update(workbench);
    }

    private Workbench newWorkbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)),
                10);
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", selection);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench Lifecycle",
                "编排 Stage 人工生命周期", AgentType.CODEX, "local",
                scope, new WorkspaceSnapshotReference(
                        "snapshot-lifecycle", topology.getTopologyHash(),
                        repeat('2'), 1),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_IDENTIFIER, stageSnapshot())), CREATED_AT);
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft(
                "implementation",
                WorkbenchStageDraftContent.create(
                        30, "开发测试", "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, CREATED_AT.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "implementation", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, CREATED_AT.minusSeconds(1));
        return WorkbenchStageSnapshot.fromPublishedRevision(revision);
    }

    private String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }
}
