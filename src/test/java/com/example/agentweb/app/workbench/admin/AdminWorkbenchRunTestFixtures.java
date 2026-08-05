package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
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

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Admin Dynamic Stage Workbench Run 应用测试的真实领域事实。
 *
 * @author alex
 * @since 2026-08-05
 */
final class AdminWorkbenchRunTestFixtures {

    static final Instant NOW = Instant.parse("2026-08-05T20:00:00Z");
    static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");
    static final String STAGE_IDENTIFIER = "stage-analysis";

    private AdminWorkbenchRunTestFixtures() {
    }

    static Workbench workbench() {
        RepositoryScope scope = scope();
        return Workbench.create(
                WORKBENCH_ID, OwnerReference.of("owner-1", "Owner One"),
                "Workbench", "Goal", AgentType.CODEX, "local", scope,
                snapshotReference(scope),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_IDENTIFIER, stageSnapshot())),
                NOW.minusSeconds(10));
    }

    static ChatRun runningRun() {
        ChatRun run = ChatRun.submit(
                ChatRunId.of("run-1"), "session-1", 1L, "submission-1",
                false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:" + STAGE_IDENTIFIER, "run-1"),
                NOW.minusSeconds(2));
        run.start(NOW.minusSeconds(1));
        return run;
    }

    static WorkbenchStageRunSnapshot snapshot() {
        RepositoryScope scope = scope();
        WorkbenchStageSnapshot frozenStage = stageSnapshot();
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "policy-1", "analysis", "1", frozenStage.getSnapshotHash(),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/safety", "1", "platform", repeat('b'),
                        true, "安全规则")),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "codex-compatible");
        return WorkbenchStageRunSnapshot.create(
                "run-1", WORKBENCH_ID, STAGE_IDENTIFIER, frozenStage,
                "submission-1", repeat('7'),
                RunMode.DISCUSS_READ_ONLY, scope,
                snapshotReference(scope), binding, null,
                0L, repeat('3'), Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(), "repo",
                        1800L, 8_388_608L),
                Collections.emptyList(), Collections.emptyList(),
                NOW.minusSeconds(2));
    }

    private static WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "analysis",
                WorkbenchStageDraftContent.create(
                        10, "分析", "分析目标", "保持只读",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(12));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "analysis", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator, NOW.minusSeconds(11)));
    }

    private static RepositoryScope scope() {
        RepositorySelection selection = RepositorySelection.of(
                "repo", Collections.singletonList("repo"));
        return RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "repo", "/workspace/repo", repeat('1'), false)),
                50);
    }

    private static WorkspaceSnapshotReference snapshotReference(
            RepositoryScope scope) {
        return new WorkspaceSnapshotReference(
                "snapshot-1",
                WorkspaceTopology.of(
                        scope.getWorkspaceRoot(),
                        RepositorySelection.of(
                                "repo", Collections.singletonList("repo")))
                        .getTopologyHash(),
                repeat('2'), 1);
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }
}
