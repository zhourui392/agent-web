package com.example.agentweb.app.workbench;

import com.example.agentweb.app.workbench.port.WorkspaceScopeGateway;
import com.example.agentweb.app.workbench.port.WorkspaceSnapshotGateway;
import com.example.agentweb.app.workbench.port.WorkspaceHandoffGuard;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.app.workbench.port.WorkbenchWorktreeGateway;
import com.example.agentweb.app.agentrun.AgentCatalogService;
import com.example.agentweb.domain.agentrun.AgentPolicyViolationException;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchCreationReceipt;
import com.example.agentweb.domain.workbench.WorkbenchCreationRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalogRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench 创建的幂等短路、外部事实准备与事务保存编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchCreationAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T07:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");

    private WorkbenchCreationRepository creationRepository;
    private WorkbenchRepository workbenchRepository;
    private WorkspaceScopeGateway scopeGateway;
    private WorkspaceSnapshotGateway snapshotGateway;
    private WorkbenchIdGenerator workbenchIdGenerator;
    private WorkspaceSnapshotIdGenerator snapshotIdGenerator;
    private WorkbenchStageCatalogRepository stageCatalogRepository;
    private WorkbenchStageInstanceIdentifierGenerator stageInstanceIdentifierGenerator;
    private AgentCatalogService agentCatalogService;
    private WorkbenchCreationCommitter committer;
    private WorkbenchReleasePolicy releasePolicy;
    private WorkbenchTelemetry telemetry;
    private WorkspaceHandoffGuard handoffGuard;
    private WorkbenchCreationAppService service;

    @BeforeEach
    void setUp() {
        creationRepository = mock(WorkbenchCreationRepository.class);
        workbenchRepository = mock(WorkbenchRepository.class);
        scopeGateway = mock(WorkspaceScopeGateway.class);
        snapshotGateway = mock(WorkspaceSnapshotGateway.class);
        workbenchIdGenerator = mock(WorkbenchIdGenerator.class);
        snapshotIdGenerator = mock(WorkspaceSnapshotIdGenerator.class);
        stageCatalogRepository = mock(WorkbenchStageCatalogRepository.class);
        stageInstanceIdentifierGenerator = mock(
                WorkbenchStageInstanceIdentifierGenerator.class);
        agentCatalogService = mock(AgentCatalogService.class);
        committer = mock(WorkbenchCreationCommitter.class);
        releasePolicy = mock(WorkbenchReleasePolicy.class);
        telemetry = mock(WorkbenchTelemetry.class);
        handoffGuard = mock(WorkspaceHandoffGuard.class);
        WorkbenchWorktreeGateway worktreeGateway = mock(
                WorkbenchWorktreeGateway.class);
        when(stageCatalogRepository.find()).thenReturn(stageCatalog());
        when(stageInstanceIdentifierGenerator.nextIdentifier())
                .thenReturn("stage-requirement", "stage-implementation");
        service = new WorkbenchCreationAppService(
                creationRepository, workbenchRepository, scopeGateway, snapshotGateway,
                workbenchIdGenerator, snapshotIdGenerator, stageCatalogRepository,
                stageInstanceIdentifierGenerator, committer, agentCatalogService,
                releasePolicy, telemetry, handoffGuard, worktreeGateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void disabledCreationShouldFailBeforeIdempotencyOrExternalSideEffects() {
        org.mockito.Mockito.doThrow(
                        WorkbenchReleaseUnavailableException.creation())
                .when(releasePolicy).requireCreationAvailable();

        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> service.create(OWNER, command()));

        verifyNoInteractions(
                creationRepository, workbenchRepository, scopeGateway,
                snapshotGateway, workbenchIdGenerator, snapshotIdGenerator,
                stageCatalogRepository, stageInstanceIdentifierGenerator,
                committer, agentCatalogService, handoffGuard);
        verify(telemetry, times(1)).workbenchCreated("FAILED");
    }

    @Test
    void duplicateShouldReplayBeforeGitResolutionOrIdGeneration() {
        CreateWorkbenchCommand command = command();
        Workbench existing = workbench("workbench-existing", scope(), snapshot("snapshot-existing"));
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                OWNER, command.getIdempotencyKey(), command.getRequestHash(),
                existing.getId(), NOW.minusSeconds(1));
        when(creationRepository.findByOwnerAndIdempotencyKey(
                OWNER, command.getIdempotencyKey())).thenReturn(Optional.of(receipt));
        when(workbenchRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        WorkbenchCreationResult result = service.create(OWNER, command);

        assertEquals("workbench-existing", result.getWorkbenchId());
        assertTrue(result.isReplayed());
        verifyNoInteractions(scopeGateway, snapshotGateway, workbenchIdGenerator,
                snapshotIdGenerator, stageCatalogRepository,
                stageInstanceIdentifierGenerator, committer, agentCatalogService,
                handoffGuard);
        verify(telemetry, times(1)).workbenchCreated("REPLAYED");
    }

    @Test
    void firstSubmissionShouldPrepareExternalFactsThenDelegateAtomicCommit() {
        CreateWorkbenchCommand command = command();
        RepositoryScope scope = scope();
        WorkspaceSnapshot snapshot = snapshot("snapshot-1");
        when(creationRepository.findByOwnerAndIdempotencyKey(
                OWNER, command.getIdempotencyKey())).thenReturn(Optional.empty());
        when(workbenchIdGenerator.nextId()).thenReturn(WorkbenchId.of("workbench-1"));
        when(snapshotIdGenerator.nextId()).thenReturn("snapshot-1");
        when(scopeGateway.resolve(command.getWorkspaceRoot(),
                command.getRepositorySelection())).thenReturn(scope);
        when(snapshotGateway.capture(
                "snapshot-1", scope, SnapshotPurpose.of("WORKBENCH_CREATE")))
                .thenReturn(snapshot);
        when(agentCatalogService.requireWorkbenchAvailable(
                AgentType.CODEX, "local")).thenReturn(AgentType.CODEX);
        when(committer.commit(any(PreparedWorkbenchCreation.class)))
                .thenAnswer(invocation -> WorkbenchCreationResult.created(
                        invocation.getArgument(0, PreparedWorkbenchCreation.class)
                                .getWorkbench()));

        WorkbenchCreationResult result = service.create(OWNER, command);

        assertEquals("workbench-1", result.getWorkbenchId());
        assertFalse(result.isReplayed());
        ArgumentCaptor<PreparedWorkbenchCreation> prepared =
                ArgumentCaptor.forClass(PreparedWorkbenchCreation.class);
        verify(committer).commit(prepared.capture());
        assertEquals(snapshot, prepared.getValue().getSnapshot());
        assertEquals(command.getRequestHash(),
                prepared.getValue().getReceipt().getRequestHash());
        assertEquals(scope, prepared.getValue().getWorkbench().getRepositoryScope());
        assertEquals(Arrays.asList("requirement-analysis", "implementation"),
                prepared.getValue().getWorkbench().getStages().stream()
                        .map(stage -> stage.getSnapshot().getDefinitionIdentifier())
                        .toList());
        InOrder order = inOrder(scopeGateway, snapshotGateway, committer);
        order.verify(scopeGateway).resolve(
                command.getWorkspaceRoot(), command.getRepositorySelection());
        order.verify(snapshotGateway).capture(
                "snapshot-1", scope, SnapshotPurpose.of("WORKBENCH_CREATE"));
        order.verify(committer).commit(any(PreparedWorkbenchCreation.class));
        verify(agentCatalogService).requireWorkbenchAvailable(
                AgentType.CODEX, "local");
        verify(telemetry, times(1)).workbenchCreated("SUCCESS");
    }

    @Test
    void unavailableWorkbenchAgentShouldFailBeforeWorkspaceResolution() {
        CreateWorkbenchCommand command = command();
        when(creationRepository.findByOwnerAndIdempotencyKey(
                OWNER, command.getIdempotencyKey())).thenReturn(Optional.empty());
        when(agentCatalogService.requireWorkbenchAvailable(
                AgentType.CODEX, "local")).thenThrow(
                new AgentPolicyViolationException(
                        "AGENT_SURFACE_UNAVAILABLE",
                        "agent is unavailable for Workbench"));

        assertThrows(AgentPolicyViolationException.class,
                () -> service.create(OWNER, command));

        verifyNoInteractions(scopeGateway, snapshotGateway, workbenchIdGenerator,
                snapshotIdGenerator, committer, handoffGuard);
    }

    @Test
    void snapshotFailureShouldNotEnterPersistenceCommit() {
        CreateWorkbenchCommand command = command();
        RepositoryScope scope = scope();
        when(creationRepository.findByOwnerAndIdempotencyKey(
                OWNER, command.getIdempotencyKey())).thenReturn(Optional.empty());
        when(workbenchIdGenerator.nextId()).thenReturn(WorkbenchId.of("workbench-1"));
        when(snapshotIdGenerator.nextId()).thenReturn("snapshot-1");
        when(scopeGateway.resolve(command.getWorkspaceRoot(),
                command.getRepositorySelection())).thenReturn(scope);
        when(snapshotGateway.capture(
                "snapshot-1", scope, SnapshotPurpose.of("WORKBENCH_CREATE")))
                .thenThrow(new IllegalStateException("capture failed"));
        when(agentCatalogService.requireWorkbenchAvailable(
                AgentType.CODEX, "local")).thenReturn(AgentType.CODEX);

        assertThrows(IllegalStateException.class, () -> service.create(OWNER, command));

        verify(committer, never()).commit(any(PreparedWorkbenchCreation.class));
        verify(telemetry, times(1)).workbenchCreated("FAILED");
    }

    @Test
    void committerShouldRecheckRaceAndPersistAllNewFactsInOneOrderedAction() {
        WorkbenchCreationRepository receipts = mock(WorkbenchCreationRepository.class);
        WorkbenchRepository workbenches = mock(WorkbenchRepository.class);
        WorkspaceSnapshotRepository snapshots = mock(WorkspaceSnapshotRepository.class);
        DefaultWorkbenchCreationCommitter target = new DefaultWorkbenchCreationCommitter(
                receipts, workbenches, snapshots, action -> action.get());
        CreateWorkbenchCommand command = command();
        WorkspaceSnapshot snapshot = snapshot("snapshot-1");
        Workbench workbench = workbench("workbench-1", scope(), snapshot);
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                OWNER, command.getIdempotencyKey(), command.getRequestHash(),
                workbench.getId(), NOW);
        when(receipts.findByOwnerAndIdempotencyKey(
                OWNER, command.getIdempotencyKey())).thenReturn(Optional.empty());

        WorkbenchCreationResult result = target.commit(
                new PreparedWorkbenchCreation(workbench, snapshot, receipt));

        assertFalse(result.isReplayed());
        InOrder order = inOrder(snapshots, workbenches, receipts);
        order.verify(snapshots).add(snapshot);
        order.verify(workbenches).add(workbench);
        order.verify(receipts).add(receipt);
    }

    @Test
    void committerConcurrentReplayShouldNotWritePreparedFacts() {
        WorkbenchCreationRepository receipts = mock(WorkbenchCreationRepository.class);
        WorkbenchRepository workbenches = mock(WorkbenchRepository.class);
        WorkspaceSnapshotRepository snapshots = mock(WorkspaceSnapshotRepository.class);
        DefaultWorkbenchCreationCommitter target = new DefaultWorkbenchCreationCommitter(
                receipts, workbenches, snapshots, action -> action.get());
        CreateWorkbenchCommand command = command();
        WorkspaceSnapshot snapshot = snapshot("snapshot-race");
        Workbench preparedWorkbench = workbench("workbench-race", scope(), snapshot);
        Workbench existing = workbench("workbench-existing", scope(), snapshot("snapshot-existing"));
        WorkbenchCreationReceipt existingReceipt = WorkbenchCreationReceipt.record(
                OWNER, command.getIdempotencyKey(), command.getRequestHash(),
                existing.getId(), NOW.minusSeconds(1));
        when(receipts.findByOwnerAndIdempotencyKey(
                OWNER, command.getIdempotencyKey())).thenReturn(Optional.of(existingReceipt));
        when(workbenches.findById(existing.getId())).thenReturn(Optional.of(existing));

        WorkbenchCreationResult result = target.commit(
                new PreparedWorkbenchCreation(
                        preparedWorkbench, snapshot,
                        WorkbenchCreationReceipt.record(
                                OWNER, command.getIdempotencyKey(), command.getRequestHash(),
                                preparedWorkbench.getId(), NOW)));

        assertEquals("workbench-existing", result.getWorkbenchId());
        assertTrue(result.isReplayed());
        verify(snapshots, never()).add(any(WorkspaceSnapshot.class));
        verify(workbenches, never()).add(any(Workbench.class));
        verify(receipts, never()).add(any(WorkbenchCreationReceipt.class));
    }

    private static CreateWorkbenchCommand command() {
        return new CreateWorkbenchCommand(
                "create-key-1", "Workbench MVP", "实现本地开发工作台",
                AgentType.CODEX, "local", "/workspace", "agent-web",
                Arrays.asList("agent-web", "shared-library"),
                Arrays.asList("implementation", "requirement-analysis"), 3L, false);
    }

    private static WorkbenchStageCatalog stageCatalog() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        publishStage(catalog, "implementation", 30, "开发测试");
        publishStage(catalog, "requirement-analysis", 10, "需求分析");
        return catalog;
    }

    private static void publishStage(
            WorkbenchStageCatalog catalog, String identifier,
            int sequenceNumber, String displayName) {
        StageCatalogEditor editor = StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(identifier, WorkbenchStageDraftContent.create(
                        sequenceNumber, displayName, "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList()),
                editor, NOW.minusSeconds(60));
        catalog.publishDraft(identifier, catalog.getCatalogVersion(),
                catalog.requireDefinition(identifier).getVersion(),
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, NOW.minusSeconds(30));
    }

    private static RepositoryScope scope() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        return RepositoryScope.create(
                "/workspace", selection,
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web", repeat('1'), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "shared-library", "/workspace/shared-library",
                                repeat('2'), false)),
                50);
    }

    private static WorkspaceSnapshot snapshot(String snapshotId) {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        WorkspaceTopology topology = WorkspaceTopology.of("/workspace", selection);
        return WorkspaceSnapshot.capture(
                snapshotId, SnapshotPurpose.of("WORKBENCH_CREATE"), topology,
                Arrays.asList(
                        RepositoryBaseline.capture(
                                "agent-web", "/workspace/agent-web", "master",
                                repeatForty('a'), true, repeat('3'), NOW),
                        RepositoryBaseline.capture(
                                "shared-library", "/workspace/shared-library", "master",
                                repeatForty('b'), true, repeat('4'), NOW)),
                Collections.emptyList(), NOW, NOW);
    }

    private static Workbench workbench(
            String workbenchId, RepositoryScope scope, WorkspaceSnapshot snapshot) {
        WorkbenchStageCatalog catalog = stageCatalog();
        return Workbench.create(
                WorkbenchId.of(workbenchId), OWNER,
                "Workbench MVP", "实现本地开发工作台", AgentType.CODEX, "local",
                scope, snapshot.reference(), Collections.singletonList(
                        WorkbenchStageState.initial(
                                "stage-implementation",
                                WorkbenchStageSnapshot.fromPublishedRevision(
                                        catalog.selectPublishedRevisions(
                                                Collections.singletonList(
                                                        "implementation"),
                                                catalog.getCatalogVersion())
                                                .get(0)))),
                NOW);
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static String repeatForty(char value) {
        StringBuilder result = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
