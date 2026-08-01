package com.example.agentweb.app.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.CredentialReference;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.HandoffSnapshotReference;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Workbench 冻结 Snapshot 到公共 Runtime Plan 的应用编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchExecutionPlanProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final String RUN_ID = "workbench-run-1";
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");

    private WorkbenchRunSnapshotRepository snapshotRepository;
    private WorkbenchRunPromptPayloadRepository promptRepository;
    private WorkbenchRepository workbenchRepository;
    private CredentialReference credentialReference;
    private WorkbenchExecutionPlanProvider provider;
    private RepositoryScope scope;
    private Workbench workbench;
    private WorkbenchRunPromptPayload promptPayload;
    private WorkbenchRunSnapshot snapshot;
    private ChatRun run;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(WorkbenchRunSnapshotRepository.class);
        promptRepository = mock(WorkbenchRunPromptPayloadRepository.class);
        workbenchRepository = mock(WorkbenchRepository.class);
        credentialReference = CredentialReference.environment(
                "AGENT_COMMON_RUNTIME_API_KEY");
        provider = new WorkbenchExecutionPlanProvider(
                snapshotRepository, promptRepository,
                workbenchRepository, credentialReference);

        scope = scope();
        WorkspaceSnapshotReference workspaceSnapshot = snapshotReference();
        workbench = Workbench.create(
                WORKBENCH_ID, OwnerReference.of("owner-1", "Alex"),
                "Workbench", "Implement the approved solution",
                AgentType.CODEX, "local", scope, workspaceSnapshot, NOW);
        promptPayload = WorkbenchRunPromptPayload.freeze(
                RUN_ID, "frozen workbench prompt",
                WorkbenchPromptHistoryDelivery.TYPED, NOW.plusSeconds(1));
        snapshot = WorkbenchRunSnapshot.create(
                RUN_ID, WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                "submit-1", repeat('1'), RunMode.MODIFY_WORKSPACE,
                scope, workspaceSnapshot, binding(), null,
                HandoffSnapshotReference.of(
                        WorkbenchPhase.SOLUTION_DESIGN, 3L, repeat('2')),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner", repeat('3'), 23)),
                promptPayload.getPromptHash(),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.145.0", scope.getScopeHash(),
                        "service-a", Arrays.asList("service-a", "service-b"),
                        1800L, 8_388_608L),
                null, NOW.plusSeconds(1));
        run = ChatRun.submit(
                ChatRunId.of(RUN_ID), "phase-session-1", 21L,
                "submit-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:IMPLEMENT_TEST", RUN_ID), NOW.plusSeconds(1));
    }

    @Test
    void shouldSupportOnlyWorkbenchOrigin() {
        assertTrue(provider.supports(RunOrigin.WORKBENCH));
        assertEquals(false, provider.supports(RunOrigin.CHAT));
    }

    @Test
    void shouldAssembleOnlyFrozenSnapshotPromptScopeAndRuntimeFacts() {
        persistAll();

        AgentExecutionPlan plan = provider.prepare(run);

        assertEquals(RUN_ID, plan.getExecutionIdentity().getExecutionId());
        assertEquals("owner-1", plan.getExecutionIdentity().getOwnerId());
        assertEquals("workbench-1:IMPLEMENT_TEST",
                plan.getExecutionIdentity().getOriginReference());
        assertEquals(AgentType.CODEX,
                plan.getRuntimeSelection().getAgentType());
        assertEquals(RuntimeVersionPolicy.Mode.EXACT,
                plan.getRuntimeSelection().getRuntimeVersionPolicy().getMode());
        assertEquals("0.145.0", plan.getRuntimeSelection()
                .getRuntimeVersionPolicy().exactVersion().get());
        assertSame(credentialReference,
                plan.getRuntimeSelection().getCredentialReference());
        assertEquals(promptPayload.getFinalPrompt(),
                plan.getPromptPayload().getFinalPrompt());
        assertEquals(promptPayload.getPromptHash(),
                plan.getPromptPayload().getPromptHash());
        assertEquals(HistoryDelivery.TYPED,
                plan.getPromptPayload().getHistoryDelivery());
        assertEquals("/workspace/service-a",
                plan.getWorkspaceLayout().getPrimaryRepositoryRoot());
        assertEquals(Arrays.asList(
                        "/workspace/service-a", "/workspace/service-b"),
                plan.getWorkspaceLayout().getReadableRoots());
        assertEquals(Arrays.asList(
                        "/workspace/service-a", "/workspace/service-b"),
                plan.getWorkspaceLayout().getWritableRoots());
        assertEquals(SandboxMode.WORKSPACE_WRITE,
                plan.getWorkspaceLayout().getSandboxMode());
        assertSame(snapshot.getCapabilityBinding(),
                plan.getCapabilityBinding());
        assertEquals(1800L,
                plan.getRuntimeLimits().getTimeout().getSeconds());
        assertEquals(8_388_608L,
                plan.getRuntimeLimits().getMaxOutputBytes());
        assertTrue(plan.getRuntimeLimits().getEnvironmentAllowlist().isEmpty());
    }

    @Test
    void shouldProjectPersistedSafeAttachmentFactsToPrivateRuntimePlan() {
        DocumentReference reference = DocumentReference.of(
                "service-b", "docs/design.md");
        VerifiedWorkbenchRunAttachment attachment =
                VerifiedWorkbenchRunAttachment.verify(
                        reference, repeat('9'), reference, repeat('9'),
                        "text/markdown", 128L, false);
        snapshot = WorkbenchRunSnapshot.create(
                RUN_ID, WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                "submit-1", repeat('1'), RunMode.MODIFY_WORKSPACE,
                scope, snapshotReference(), binding(), null,
                HandoffSnapshotReference.of(
                        WorkbenchPhase.SOLUTION_DESIGN, 3L, repeat('2')),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner", repeat('3'), 23)),
                promptPayload.getPromptHash(),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.145.0", scope.getScopeHash(),
                        "service-a", Arrays.asList("service-a", "service-b"),
                        1800L, 8_388_608L),
                Collections.singletonList(attachment), null, NOW.plusSeconds(1));
        persistAll();

        AgentExecutionPlan plan = provider.prepare(run);

        assertEquals(1, plan.getAttachmentExpectations().size());
        assertEquals("service-b", plan.getAttachmentExpectations().get(0)
                .getRepositoryKey());
        assertEquals("/workspace/service-b", plan.getAttachmentExpectations().get(0)
                .getRepositoryRoot());
        assertEquals("docs/design.md", plan.getAttachmentExpectations().get(0)
                .getRelativePath());
        assertEquals(repeat('9'), plan.getAttachmentExpectations().get(0)
                .getContentHash());
        assertEquals(128L, plan.getAttachmentExpectations().get(0).getSize());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getAttachmentExpectations().clear());
    }

    @Test
    void missingSnapshotPromptOrWorkbenchShouldFailClosed() {
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
    void mismatchedPrivatePromptShouldBeRejectedBySnapshotAggregate() {
        when(snapshotRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(snapshot));
        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(WorkbenchRunPromptPayload.freeze(
                        "another-run", "frozen workbench prompt",
                        WorkbenchPromptHistoryDelivery.TYPED,
                        NOW.plusSeconds(1))));

        assertThrows(RuntimeException.class,
                () -> provider.prepare(run));
    }

    @Test
    void mismatchedChatRunOriginReferenceShouldBeRejectedBySnapshotAggregate() {
        persistAll();
        ChatRun mismatched = ChatRun.submit(
                ChatRunId.of(RUN_ID), "phase-session-1", 21L,
                "submit-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:SOLUTION_DESIGN", RUN_ID), NOW.plusSeconds(1));

        assertThrows(RuntimeException.class,
                () -> provider.prepare(mismatched));
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

    private ResolvedCapabilityBinding binding() {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "implementation", "1", repeat('e'),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                "common-runtime@1");
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }
}
