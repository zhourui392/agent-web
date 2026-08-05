package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench 创建幂等收据的输入绑定测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchCreationReceiptTest {

    private static final Instant NOW = Instant.parse("2026-08-01T06:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");

    @Test
    void sameOwnerKeyAndRequestShouldReplayOriginalWorkbench() {
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                OWNER, "create-key-1", repeat('a'),
                WorkbenchId.of("workbench-1"), NOW);

        assertEquals(WorkbenchId.of("workbench-1"),
                receipt.requireReplay(OWNER, "create-key-1", repeat('a')));
    }

    @Test
    void sameKeyWithDifferentCanonicalRequestShouldConflict() {
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                OWNER, "create-key-1", repeat('a'),
                WorkbenchId.of("workbench-1"), NOW);

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> receipt.requireReplay(
                        OWNER, "create-key-1", repeat('b')));

        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT, conflict.getCode());
    }

    @Test
    void receiptShouldRejectAnotherOwnerOrMalformedIdentityFacts() {
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                OWNER, "create-key-1", repeat('a'),
                WorkbenchId.of("workbench-1"), NOW);

        assertThrows(WorkbenchDomainException.class,
                () -> receipt.requireReplay(
                        OwnerReference.of("user-2", "Other"),
                        "create-key-1", repeat('a')));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchCreationReceipt.restore(
                        OWNER, " ", repeat('a'),
                        WorkbenchId.of("workbench-1"), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchCreationReceipt.restore(
                        OWNER, "create-key-1", "bad-hash",
                        WorkbenchId.of("workbench-1"), NOW));
    }

    @Test
    void preparedCreationShouldRequireTheSameWorkbenchOwnerSnapshotAndTime() {
        WorkspaceSnapshot snapshot = snapshot("snapshot-1");
        Workbench workbench = workbench(
                WorkbenchId.of("workbench-1"), OWNER, snapshot, NOW);
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                OWNER, "create-key-1", repeat('a'), workbench.getId(), NOW);

        receipt.requirePreparedFacts(workbench, snapshot);

        assertThrows(IllegalArgumentException.class,
                () -> receipt.requirePreparedFacts(
                        workbench(WorkbenchId.of("workbench-2"), OWNER, snapshot, NOW),
                        snapshot));
        assertThrows(IllegalArgumentException.class,
                () -> receipt.requirePreparedFacts(
                        workbench(
                                WorkbenchId.of("workbench-1"),
                                OwnerReference.of("user-2", "Other"), snapshot, NOW),
                        snapshot));
        assertThrows(IllegalArgumentException.class,
                () -> receipt.requirePreparedFacts(
                        workbench, snapshot("snapshot-2")));
        assertThrows(IllegalArgumentException.class,
                () -> receipt.requirePreparedFacts(
                        workbench(
                                WorkbenchId.of("workbench-1"), OWNER, snapshot,
                                NOW.plusSeconds(1)),
                        snapshot));
    }

    private static Workbench workbench(
            WorkbenchId id, OwnerReference owner, WorkspaceSnapshot snapshot,
            Instant createdAt) {
        return Workbench.create(
                id, owner, "Workbench", "Goal", AgentType.CODEX, "local",
                scope(), snapshot.reference(),
                Collections.singletonList(stageState(createdAt)), createdAt);
    }

    private static WorkbenchStageState stageState(Instant createdAt) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor editor = StageCatalogEditor.create(
                "admin-1", "Admin");
        catalog.createDraft(
                "goal-analysis",
                WorkbenchStageDraftContent.create(
                        10, "目标分析", "分析创建目标", "固定创建输入",
                        Collections.singleton(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, createdAt.minusNanos(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "goal-analysis", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, createdAt.minusNanos(1));
        return WorkbenchStageState.initial(
                "stage-goal-analysis",
                WorkbenchStageSnapshot.fromPublishedRevision(revision));
    }

    private static RepositoryScope scope() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        return RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(ResolvedRepository.fromVerifiedFacts(
                        "agent-web", "/workspace/agent-web", repeat('f'), false)),
                50);
    }

    private static WorkspaceSnapshot snapshot(String snapshotId) {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        WorkspaceTopology topology = WorkspaceTopology.of("/workspace", selection);
        return WorkspaceSnapshot.capture(
                snapshotId, SnapshotPurpose.of("WORKBENCH_CREATE"), topology,
                Collections.singletonList(RepositoryBaseline.capture(
                        "agent-web", "/workspace/agent-web", "master",
                        repeatForty('a'), true, repeat('b'), NOW)),
                Collections.emptyList(), NOW, NOW);
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
