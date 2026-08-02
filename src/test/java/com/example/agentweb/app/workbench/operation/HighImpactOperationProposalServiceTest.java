package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationProposalReceipt;
import com.example.agentweb.domain.workbench.HighImpactOperationProposalRepository;
import com.example.agentweb.domain.workbench.HighImpactOperationRepository;
import com.example.agentweb.domain.workbench.HighImpactOperationStatus;
import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.LocalDeployTarget;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ProductionWriteTarget;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.PushTarget;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author alex
 * @since 2026-08-01
 */
@ExtendWith(MockitoExtension.class)
class HighImpactOperationProposalServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T18:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference OTHER = OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");

    @Mock
    private WorkbenchRepository workbenchRepository;
    @Mock
    private WorkbenchRunSnapshotRepository runSnapshotRepository;
    @Mock
    private HighImpactOperationRepository operationRepository;
    @Mock
    private HighImpactOperationProposalRepository proposalRepository;
    @Mock
    private HighImpactOperationIdGenerator idGenerator;
    @Mock
    private WorkbenchTelemetry telemetry;

    private HighImpactOperationProposalService service;
    private Workbench workbench;
    private WorkbenchRunSnapshot sourceRun;

    @BeforeEach
    void setUp() {
        workbench = workbench(WORKBENCH_ID, OWNER);
        sourceRun = snapshot("run-1", WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, workbench.getRepositoryScope());
        service = new HighImpactOperationProposalService(
                workbenchRepository, runSnapshotRepository,
                operationRepository, proposalRepository, idGenerator,
                HighImpactOperationPolicy.withAuthorizationTtl(Duration.ofMinutes(15)),
                Clock.fixed(NOW, ZoneOffset.UTC), telemetry);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
    }

    @Test
    void fourTypedTargetsShouldCreateOnlyProposedOperationsFromExactRunMetadata() {
        HighImpactOperationTarget[] targets = new HighImpactOperationTarget[] {
                commitTarget("agent-web"),
                PushTarget.create("agent-web", "origin", "master",
                        "refs/heads/master", repeat('1', 40)),
                LocalDeployTarget.create("service", "1", repeat('2', 64),
                        Collections.singletonList("agent-web"), repeat('3', 64),
                        "恢复旧进程"),
                ProductionWriteTarget.describe(
                        "production", "database/orders", repeat('4', 64))
        };
        when(runSnapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(sourceRun));
        for (int index = 0; index < targets.length; index++) {
            String operationId = "operation-" + index;
            String idempotencyKey = "proposal-" + index;
            when(idGenerator.nextId()).thenReturn(operationId);

            HighImpactOperationProjection result = service.propose(
                    OWNER, WORKBENCH_ID,
                    new ProposeHighImpactOperationCommand(
                            idempotencyKey, "run-1", WorkbenchPhase.REQUIREMENT_ANALYSIS,
                            targets[index], "人工核对后的安全预览"));

            assertEquals(operationId, result.getOperationId());
            assertEquals(targets[index].getType(), result.getType());
            assertEquals(HighImpactOperationStatus.PROPOSED, result.getStatus());
            assertEquals(0L, result.getVersion());
            verify(telemetry).operation(
                    targets[index].getType(), HighImpactOperationStatus.PROPOSED.name());
        }
        ArgumentCaptor<HighImpactOperation> saved =
                ArgumentCaptor.forClass(HighImpactOperation.class);
        verify(operationRepository, org.mockito.Mockito.times(4)).add(saved.capture());
        assertEquals("Run run-1 (REQUIREMENT_ANALYSIS)",
                saved.getAllValues().get(0).getSourceRun().getSafeSummary());
        verify(proposalRepository, org.mockito.Mockito.times(4))
                .add(any(HighImpactOperationProposalReceipt.class));
    }

    @Test
    void sourceRunMustBelongToExactWorkbenchAndPhaseAndTargetMustStayInScope() {
        WorkbenchRunSnapshot foreign = snapshot(
                "foreign", WorkbenchId.of("workbench-2"),
                WorkbenchPhase.REQUIREMENT_ANALYSIS, workbench.getRepositoryScope());
        when(runSnapshotRepository.findByRunId("foreign"))
                .thenReturn(Optional.of(foreign));
        when(idGenerator.nextId()).thenReturn("operation-foreign");

        assertThrows(IllegalArgumentException.class,
                () -> service.propose(OWNER, WORKBENCH_ID,
                        command("key-foreign", "foreign",
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                commitTarget("agent-web"))));

        WorkbenchRunSnapshot wrongPhase = snapshot(
                "wrong-phase", WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, workbench.getRepositoryScope());
        when(runSnapshotRepository.findByRunId("wrong-phase"))
                .thenReturn(Optional.of(wrongPhase));
        assertThrows(IllegalArgumentException.class,
                () -> service.propose(OWNER, WORKBENCH_ID,
                        command("key-phase", "wrong-phase",
                                WorkbenchPhase.SOLUTION_DESIGN,
                                commitTarget("agent-web"))));

        when(runSnapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(sourceRun));
        assertThrows(IllegalArgumentException.class,
                () -> service.propose(OWNER, WORKBENCH_ID,
                        command("key-scope", "run-1",
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                commitTarget("outside"))));

        verify(operationRepository, never()).add(any(HighImpactOperation.class));
        verify(proposalRepository, never()).add(any(HighImpactOperationProposalReceipt.class));
    }

    @Test
    void missingOwnerAndArchivedWorkbenchShouldFailBeforeRunLookup() {
        OperationApplicationException missing = assertThrows(
                OperationApplicationException.class,
                () -> service.propose(OTHER, WORKBENCH_ID,
                        command("key-owner", "run-1",
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                commitTarget("agent-web"))));
        assertEquals(OperationApplicationErrorCode.WORKBENCH_NOT_FOUND,
                missing.getCode());
        verifyNoInteractions(runSnapshotRepository, operationRepository,
                proposalRepository, idGenerator);

        Workbench archived = workbench(WORKBENCH_ID, OWNER);
        archived.archive(OWNER, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(archived));
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.propose(OWNER, WORKBENCH_ID,
                        command("key-archived", "run-1",
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                commitTarget("agent-web"))));
        assertEquals(WorkbenchErrorCode.ARCHIVED, failure.getCode());
        verifyNoInteractions(runSnapshotRepository, operationRepository,
                proposalRepository, idGenerator);
    }

    @Test
    void repeatedIdempotencyKeyShouldReplaySameOperationAndRejectDifferentRequest() {
        ProposeHighImpactOperationCommand command = command(
                "stable-key", "run-1", WorkbenchPhase.REQUIREMENT_ANALYSIS,
                commitTarget("agent-web"));
        HighImpactOperation existing = HighImpactOperationPolicy
                .withAuthorizationTtl(Duration.ofMinutes(15))
                .propose(workbench, "operation-existing", sourceRun,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, command.getTarget(),
                        command.getSafeSummary(), OWNER, NOW.minusSeconds(1));
        HighImpactOperationProposalReceipt receipt =
                HighImpactOperationProposalReceipt.record(
                        OWNER, WORKBENCH_ID, command.getIdempotencyKey(),
                        command.getRequestHash(), existing.getOperationId(),
                        NOW.minusSeconds(1));
        when(proposalRepository.find(
                OWNER, WORKBENCH_ID, "stable-key"))
                .thenReturn(Optional.of(receipt));
        when(operationRepository.findById("operation-existing"))
                .thenReturn(Optional.of(existing));

        HighImpactOperationProjection replayed = service.propose(
                OWNER, WORKBENCH_ID, command);

        assertEquals("operation-existing", replayed.getOperationId());
        verifyNoInteractions(runSnapshotRepository, idGenerator);
        verify(operationRepository, never()).add(any(HighImpactOperation.class));

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> service.propose(OWNER, WORKBENCH_ID,
                        command("stable-key", "run-1",
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                PushTarget.create(
                                        "agent-web", "origin", "master",
                                        "refs/heads/master", repeat('1', 40)))));
        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT, conflict.getCode());
    }

    @Test
    void safeSummaryMustRejectCredentialMaterialInDomain() {
        when(runSnapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(sourceRun));
        when(idGenerator.nextId()).thenReturn("operation-secret");

        assertThrows(IllegalArgumentException.class,
                () -> service.propose(OWNER, WORKBENCH_ID,
                        new ProposeHighImpactOperationCommand(
                                "key-secret", "run-1",
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                commitTarget("agent-web"),
                                "api_key=sk-super-secret-value")));
        verify(operationRepository, never()).add(any(HighImpactOperation.class));
    }

    private ProposeHighImpactOperationCommand command(
            String idempotencyKey, String runId, WorkbenchPhase phase,
            HighImpactOperationTarget target) {
        return new ProposeHighImpactOperationCommand(
                idempotencyKey, runId, phase, target, "安全人工预览");
    }

    private CommitTarget commitTarget(String repositoryKey) {
        return CommitTarget.create(
                repositoryKey, "master", repeat('a', 40), repeat('b', 64),
                Collections.singletonList(
                        DocumentReference.of(repositoryKey, "README.md")),
                repeat('c', 64), "feat: proposal");
    }

    private Workbench workbench(WorkbenchId id, OwnerReference owner) {
        RepositoryScope scope = scope();
        return Workbench.create(
                id, owner, "Workbench", "Operation proposal",
                AgentType.CODEX, "local", scope,
                snapshotReference(scope), NOW.minusSeconds(60));
    }

    private WorkbenchRunSnapshot snapshot(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase,
            RepositoryScope scope) {
        return WorkbenchRunSnapshot.create(
                runId, workbenchId, phase, "submit-" + runId, repeat('5', 64),
                RunMode.DISCUSS_READ_ONLY, scope, snapshotReference(scope),
                ResolvedCapabilityBinding.resolve(
                        "policy-1", "profile", "1", repeat('6', 64),
                        Collections.singletonList(new ResolvedRuleBinding(
                                "platform/safety", "1", "platform",
                                repeat('7', 64), true, "安全规则")),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), "codex-compatible"),
                null, null,
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "user", repeat('8', 64), 10)),
                repeat('9', 64),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "1", scope.getScopeHash(), "agent-web",
                        60L, 1024L), null, NOW.minusSeconds(10));
    }

    private RepositoryScope scope() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        return RepositoryScope.create(
                "/workspace", selection,
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('d', 64), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "shared-library", "/workspace/shared-library",
                                repeat('e', 64), false)), 50);
    }

    private WorkspaceSnapshotReference snapshotReference(RepositoryScope scope) {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        return new WorkspaceSnapshotReference(
                "snapshot-1",
                WorkspaceTopology.of(scope.getWorkspaceRoot(), selection)
                        .getTopologyHash(), repeat('f', 64), 2);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }
}
