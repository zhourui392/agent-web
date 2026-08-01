package com.example.agentweb.app.workbench.run;

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
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;

import java.time.Instant;
import java.util.Collections;

/**
 * Workbench Run 应用测试的真实领域事实。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchRunTestFixtures {

    static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");
    static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");

    private WorkbenchRunTestFixtures() {
    }

    static Workbench workbench() {
        RepositoryScope scope = scope();
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Implement Run API",
                AgentType.CODEX, "local", scope,
                snapshotReference(scope), NOW.minusSeconds(10));
    }

    static ChatRun runningRun() {
        ChatRun run = ChatRun.submit(
                ChatRunId.of("run-1"), "session-1", 1L,
                "submit-run-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:REQUIREMENT_ANALYSIS", "run-1"),
                NOW.minusSeconds(2));
        run.start(NOW.minusSeconds(1));
        return run;
    }

    static ChatRun pendingRun() {
        return ChatRun.submit(
                ChatRunId.of("run-1"), "session-1", 1L,
                "submit-run-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:REQUIREMENT_ANALYSIS", "run-1"),
                NOW.minusSeconds(2));
    }

    static WorkbenchRunSnapshot snapshot() {
        return snapshot(binding());
    }

    static WorkbenchRunSnapshot snapshot(
            ResolvedCapabilityBinding capabilityBinding) {
        RepositoryScope scope = scope();
        return WorkbenchRunSnapshot.create(
                "run-1", WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-run-1", repeat('7'),
                RunMode.DISCUSS_READ_ONLY, scope,
                snapshotReference(scope), capabilityBinding, null, null,
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(), "repo",
                        1800L, 8388608L),
                null, NOW.minusSeconds(2));
    }

    private static RepositoryScope scope() {
        RepositorySelection selection = RepositorySelection.of(
                "repo", Collections.singletonList("repo"));
        return RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "repo", "/workspace/repo",
                                repeat('1'), false)),
                50);
    }

    private static WorkspaceSnapshotReference snapshotReference(
            RepositoryScope scope) {
        RepositorySelection selection = RepositorySelection.of(
                "repo", Collections.singletonList("repo"));
        return new WorkspaceSnapshotReference(
                "snapshot-1",
                WorkspaceTopology.of(
                        scope.getWorkspaceRoot(), selection)
                        .getTopologyHash(),
                repeat('2'), 1);
    }

    private static ResolvedCapabilityBinding binding() {
        return ResolvedCapabilityBinding.resolve(
                "policy-1", "requirement-analysis", "1", repeat('a'),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/safety", "1", "platform", repeat('b'),
                        true, "安全规则")),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "codex-compatible");
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
