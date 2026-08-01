package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.app.workbench.WorkbenchReleasePolicy;
import com.example.agentweb.app.workbench.WorkbenchReleaseUnavailableException;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationDecision;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationRepository;
import com.example.agentweb.domain.workbench.HighImpactOperationStatus;
import com.example.agentweb.domain.workbench.HighImpactPreflightProof;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author alex
 * @since 2026-08-01
 */
@ExtendWith(MockitoExtension.class)
class HighImpactOperationOwnerServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T04:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");

    @Mock
    private WorkbenchRepository workbenchRepository;

    @Mock
    private HighImpactOperationRepository operationRepository;

    @Mock
    private HighImpactOperationQueryService queryService;

    @Mock
    private WorkbenchTelemetry telemetry;

    private HighImpactOperationOwnerService service;
    private Workbench workbench;
    private HighImpactOperation operation;

    @BeforeEach
    void setUp() {
        workbench = workbench();
        operation = operation(workbench);
        service = new HighImpactOperationOwnerService(
                workbenchRepository, operationRepository, queryService,
                HighImpactOperationPolicy.withAuthorizationTtl(Duration.ofMinutes(15)),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC), telemetry);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
    }

    @Test
    void approveShouldPersistExactVersionAndRemainManualWhenExecutorIsClosed() {
        stubOperation();

        HighImpactOperationProjection result = service.decide(
                OWNER, WORKBENCH_ID, "operation-1", 0L,
                HighImpactOperationDecision.APPROVE,
                "已核对仓库、分支、状态和精确文件");

        assertEquals(HighImpactOperationStatus.AUTHORIZED, result.getStatus());
        assertEquals(1L, result.getVersion());
        assertFalse(result.isExecutionAvailable());
        assertEquals(OperationExecutionMode.MANUAL_OR_DEFERRED,
                result.getExecutionMode());
        assertEquals("agent-web", result.getTarget().getRepositoryKeys().get(0));
        assertEquals("master", result.getTarget().getDetails().get("branch"));
        verify(operationRepository).update(operation);
        verify(telemetry).operation(
                operation.getType(), HighImpactOperationStatus.AUTHORIZED.name());

        WorkbenchReleasePolicy closedExecutors =
                new WorkbenchReleasePolicy(
                        true, true, true,
                        false, false, false, false);
        assertFalse(closedExecutors.isHighImpactExecutionAvailable(
                operation.getType()));
        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> closedExecutors
                        .requireHighImpactExecutionAvailable(
                                operation.getType()));
        HighImpactPreflightProof proof =
                HighImpactPreflightProof.verified(
                        operation.getRequestedPayloadHash(),
                        operation.getTarget().expectedStateBinding(),
                        repeat('f', 64), NOW.plusSeconds(31));
        WorkbenchDomainException unavailable = assertThrows(
                WorkbenchDomainException.class,
                () -> HighImpactOperationPolicy
                        .withAuthorizationTtl(Duration.ofMinutes(15))
                        .issueExecutionPermit(
                                workbench, operation, OWNER, proof,
                                closedExecutors
                                        .isHighImpactExecutionAvailable(
                                                operation.getType()),
                                "permit-1", NOW.plusSeconds(32)));
        assertEquals(WorkbenchErrorCode.OPERATION_EXECUTION_UNAVAILABLE,
                unavailable.getCode());
        assertEquals(HighImpactOperationStatus.AUTHORIZED,
                operation.getStatus());
    }

    @Test
    void rejectShouldPersistTheActualOwnerDecisionWithoutExecution() {
        stubOperation();

        HighImpactOperationProjection result = service.decide(
                OWNER, WORKBENCH_ID, "operation-1", 0L,
                HighImpactOperationDecision.REJECT, "暂不执行");

        assertEquals(HighImpactOperationStatus.REJECTED, result.getStatus());
        assertEquals("暂不执行", result.getDecisionReason());
        assertFalse(result.isExecutionAvailable());
        verify(operationRepository).update(operation);
        verify(telemetry).operation(
                operation.getType(), HighImpactOperationStatus.REJECTED.name());
    }

    @Test
    void staleVersionShouldFailBeforeMutationAndExposeOnlySafeCurrentProjection() {
        stubOperation();

        OperationApplicationException failure = assertThrows(
                OperationApplicationException.class,
                () -> service.decide(
                        OWNER, WORKBENCH_ID, "operation-1", 7L,
                        HighImpactOperationDecision.APPROVE, "过期页面"));

        assertEquals(OperationApplicationErrorCode.VERSION_CONFLICT,
                failure.getCode());
        assertEquals(0L, failure.getCurrent().getVersion());
        assertEquals("Commit selected files", failure.getCurrent().getSafeSummary());
        verify(operationRepository, never()).update(operation);
        verify(telemetry).writeConflict();
    }

    @Test
    void nonOwnerAndCrossWorkbenchLookupShouldBothBeHiddenAsNotFound() {
        OperationApplicationException nonOwner = assertThrows(
                OperationApplicationException.class,
                () -> service.find(
                        OwnerReference.of("other", "Other"),
                        WORKBENCH_ID, "operation-1"));
        assertEquals(OperationApplicationErrorCode.WORKBENCH_NOT_FOUND,
                nonOwner.getCode());

        HighImpactOperation foreign = operation(workbench(
                WorkbenchId.of("workbench-2"), OWNER));
        when(operationRepository.findById("foreign-operation"))
                .thenReturn(Optional.of(foreign));

        OperationApplicationException crossWorkbench = assertThrows(
                OperationApplicationException.class,
                () -> service.find(
                        OWNER, WORKBENCH_ID, "foreign-operation"));
        assertEquals(OperationApplicationErrorCode.OPERATION_NOT_FOUND,
                crossWorkbench.getCode());
    }

    @Test
    void listShouldAuthorizeOwnerBeforeCallingTheReadSideQuery() {
        HighImpactOperationProjection projection =
                HighImpactOperationProjection.from(operation);
        when(queryService.findByWorkbenchId(WORKBENCH_ID))
                .thenReturn(Collections.singletonList(projection));

        assertEquals(Collections.singletonList(projection),
                service.list(OWNER, WORKBENCH_ID));
        verify(queryService).findByWorkbenchId(WORKBENCH_ID);

        assertThrows(OperationApplicationException.class,
                () -> service.list(
                        OwnerReference.of("other", "Other"), WORKBENCH_ID));
        verify(queryService).findByWorkbenchId(WORKBENCH_ID);
    }

    private HighImpactOperation operation(Workbench ownerWorkbench) {
        return HighImpactOperationPolicy.withAuthorizationTtl(Duration.ofMinutes(15))
                .propose(ownerWorkbench, "operation-1",
                        WorkbenchRunReference.of(
                                "run-1", ownerWorkbench.getId(),
                                WorkbenchPhase.IMPLEMENT_TEST, "safe run summary"),
                        CommitTarget.create(
                                "agent-web", "master", repeat('a', 40), repeat('b', 64),
                                Arrays.asList(
                                        DocumentReference.of("agent-web", "README.md"),
                                        DocumentReference.of(
                                                "agent-web", "src/main/App.java")),
                                repeat('c', 64), "feat: workbench"),
                        "Commit selected files", OWNER, NOW);
    }

    private void stubOperation() {
        when(operationRepository.findById("operation-1"))
                .thenReturn(Optional.of(operation));
    }

    private Workbench workbench() {
        return workbench(WORKBENCH_ID, OWNER);
    }

    private Workbench workbench(WorkbenchId id, OwnerReference owner) {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web", repeat('d', 64), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "shared-library", "/workspace/shared-library",
                                repeat('e', 64), false)),
                50);
        WorkspaceTopology topology = WorkspaceTopology.of("/workspace", selection);
        return Workbench.create(
                id, owner, "Workbench MVP", "Implement the workbench",
                AgentType.CODEX, "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", topology.getTopologyHash(),
                        CanonicalHashing.sha256("workspace-state"), 2),
                NOW.minusSeconds(60));
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
