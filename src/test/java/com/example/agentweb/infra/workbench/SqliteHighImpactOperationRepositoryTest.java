package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.operation.HighImpactOperationProjection;
import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationDecision;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationStatus;
import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.HighImpactPreflightProof;
import com.example.agentweb.domain.workbench.LocalDeployTarget;
import com.example.agentweb.domain.workbench.OperationExecutionPermit;
import com.example.agentweb.domain.workbench.ProductionWriteTarget;
import com.example.agentweb.domain.workbench.PushTarget;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.infra.workbench.query.SqliteHighImpactOperationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 四类高影响操作 Target、状态恢复和乐观锁的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteHighImpactOperationRepositoryTest {

    private static final HighImpactOperationPolicy POLICY =
            HighImpactOperationPolicy.withAuthorizationTtl(Duration.ofMinutes(15));

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteHighImpactOperationRepository repository;
    private Workbench workbench;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(tempDir.resolve("operation.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "operation-creation-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(workspace, "workbench-operation");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        repository = new SqliteHighImpactOperationRepository(jdbc);
    }

    @Test
    void allFourTypedTargetsShouldRoundTripWithoutCredentialOrCommandFields() {
        HighImpactOperation[] operations = new HighImpactOperation[] {
                propose("commit-op", commitTarget()),
                propose("push-op", PushTarget.create(
                        "agent-web", "origin", "feature/workbench",
                        "refs/heads/feature/workbench",
                        WorkbenchPersistenceFixtures.HEAD_A)),
                propose("deploy-op", LocalDeployTarget.create(
                        "local-service", "2", WorkbenchPersistenceFixtures.HASH_A,
                        Arrays.asList("service-api", "agent-web"),
                        WorkbenchPersistenceFixtures.HASH_B,
                        "停止新进程并恢复旧版本")),
                propose("production-op", ProductionWriteTarget.describe(
                        "production", "database/orders",
                        WorkbenchPersistenceFixtures.HASH_C))
        };

        for (HighImpactOperation operation : operations) {
            repository.add(operation);
            HighImpactOperation restored = repository.findById(operation.getOperationId())
                    .orElseThrow(AssertionError::new);
            assertOperation(operation, restored);
            assertTarget(operation.getTarget(), restored.getTarget());
        }
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_high_impact_operation", Integer.class));
    }

    @Test
    void executingAndReconciliationFieldsShouldRoundTripWithoutReplayingSideEffects() {
        HighImpactOperation operation = propose("reconcile-op", commitTarget());
        POLICY.decide(workbench, operation, OWNER, HighImpactOperationDecision.APPROVE,
                "已核对精确路径", NOW.plusSeconds(1));
        HighImpactPreflightProof proof = HighImpactPreflightProof.verified(
                operation.getRequestedPayloadHash(),
                operation.getTarget().expectedStateBinding(),
                WorkbenchPersistenceFixtures.HASH_F, NOW.plusSeconds(2));
        OperationExecutionPermit permit = POLICY.issueExecutionPermit(
                workbench, operation, OWNER, proof, true,
                "permit-1", NOW.plusSeconds(3));
        operation.startExecution(permit, "execution-1", NOW.plusSeconds(4));
        operation.requireReconciliation("UNKNOWN_TERMINAL", NOW.plusSeconds(5));

        repository.add(operation);

        HighImpactOperation restored = repository.findById(operation.getOperationId())
                .orElseThrow(AssertionError::new);
        assertOperation(operation, restored);
        assertEquals(HighImpactOperationStatus.RECONCILIATION_REQUIRED,
                restored.getStatus());
        assertEquals("execution-1", restored.getExecutionReference());
        assertEquals("UNKNOWN_TERMINAL", restored.getFailureCode());
        assertEquals(WorkbenchPersistenceFixtures.HASH_F, restored.getPreflightHash());
    }

    @Test
    void updateShouldUseOptimisticVersionAndKeepWinningDecision() {
        repository.add(propose("decision-op", commitTarget()));
        HighImpactOperation winner = repository.findById("decision-op")
                .orElseThrow(AssertionError::new);
        HighImpactOperation stale = repository.findById("decision-op")
                .orElseThrow(AssertionError::new);
        POLICY.decide(workbench, winner, OWNER, HighImpactOperationDecision.APPROVE,
                "winner", NOW.plusSeconds(1));
        POLICY.decide(workbench, stale, OWNER, HighImpactOperationDecision.REJECT,
                "stale", NOW.plusSeconds(1));

        repository.update(winner);

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class, () -> repository.update(stale));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, conflict.getCode());
        HighImpactOperation restored = repository.findById("decision-op")
                .orElseThrow(AssertionError::new);
        assertEquals(HighImpactOperationStatus.AUTHORIZED, restored.getStatus());
        assertEquals("winner", restored.getDecisionReason());
    }

    @Test
    void malformedTargetJsonPayloadHashMismatchAndDuplicateIdShouldFailFast() {
        HighImpactOperation malformed = propose("malformed-op", commitTarget());
        repository.add(malformed);
        jdbc.update("UPDATE workbench_high_impact_operation SET target_json=? "
                + "WHERE operation_id=?", "{}", malformed.getOperationId());
        assertThrows(IllegalStateException.class,
                () -> repository.findById(malformed.getOperationId()));

        HighImpactOperation hashMismatch = propose("hash-op", commitTarget());
        repository.add(hashMismatch);
        jdbc.update("UPDATE workbench_high_impact_operation "
                        + "SET requested_payload_hash=? WHERE operation_id=?",
                WorkbenchPersistenceFixtures.HASH_F, hashMismatch.getOperationId());
        assertThrows(IllegalStateException.class,
                () -> repository.findById(hashMismatch.getOperationId()));

        HighImpactOperation duplicate = propose("duplicate-op", commitTarget());
        repository.add(duplicate);
        assertThrows(IllegalStateException.class, () -> repository.add(duplicate));
        assertFalse(repository.findById("missing").isPresent());
    }

    @Test
    void ownerQueryShouldReturnBoundedSafeProjectionsInNewestFirstOrder() {
        repository.add(propose("operation-a", commitTarget()));
        repository.add(propose("operation-b", PushTarget.create(
                "agent-web", "origin", "feature/workbench",
                "refs/heads/feature/workbench",
                WorkbenchPersistenceFixtures.HEAD_A)));
        SqliteHighImpactOperationQueryService queryService =
                new SqliteHighImpactOperationQueryService(jdbc, repository);

        List<HighImpactOperationProjection> operations =
                queryService.findByWorkbenchId(workbench.getId());

        assertEquals(2, operations.size());
        assertEquals("operation-b", operations.get(0).getOperationId());
        assertEquals("operation-a", operations.get(1).getOperationId());
        assertEquals(false, operations.get(0).isExecutionAvailable());
        assertEquals(Collections.singletonList("agent-web"),
                operations.get(0).getTarget().getRepositoryKeys());
    }

    private HighImpactOperation propose(String operationId,
                                        HighImpactOperationTarget target) {
        return POLICY.propose(
                workbench, operationId,
                WorkbenchRunReference.of(
                        "source-run-" + operationId, workbench.getId(),
                        WorkbenchPhase.IMPLEMENT_TEST, "开发运行完成"),
                target, "高影响操作 " + operationId, OWNER, NOW);
    }

    private CommitTarget commitTarget() {
        return CommitTarget.create(
                "agent-web", "feature/workbench", WorkbenchPersistenceFixtures.HEAD_A,
                WorkbenchPersistenceFixtures.HASH_D,
                Arrays.asList(
                        DocumentReference.of("agent-web", "README.md"),
                        DocumentReference.of("agent-web", "src/App.java")),
                WorkbenchPersistenceFixtures.HASH_E, "feat: workbench");
    }

    private void assertOperation(HighImpactOperation expected,
                                 HighImpactOperation actual) {
        assertEquals(expected.getOperationId(), actual.getOperationId());
        assertEquals(expected.getWorkbenchId(), actual.getWorkbenchId());
        assertEquals(expected.getSourceRun(), actual.getSourceRun());
        assertEquals(expected.getSourceRun().getSafeSummary(),
                actual.getSourceRun().getSafeSummary());
        assertEquals(expected.getPhase(), actual.getPhase());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getRequestedPayloadHash(), actual.getRequestedPayloadHash());
        assertEquals(expected.getSafeSummary(), actual.getSafeSummary());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getProposedBy(), actual.getProposedBy());
        assertEquals(expected.getProposedAt(), actual.getProposedAt());
        assertEquals(expected.getDecidedBy(), actual.getDecidedBy());
        assertEquals(expected.getDecisionReason(), actual.getDecisionReason());
        assertEquals(expected.getDecidedAt(), actual.getDecidedAt());
        assertEquals(expected.getAuthorizationExpiresAt(), actual.getAuthorizationExpiresAt());
        assertEquals(expected.getPreflightHash(), actual.getPreflightHash());
        assertEquals(expected.getExecutionReference(), actual.getExecutionReference());
        assertEquals(expected.getFailureCode(), actual.getFailureCode());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.getVersion(), actual.getVersion());
    }

    private void assertTarget(HighImpactOperationTarget expected,
                              HighImpactOperationTarget actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.requestedPayloadHash(), actual.requestedPayloadHash());
        assertEquals(expected.expectedStateBinding(), actual.expectedStateBinding());
        assertEquals(expected.repositoryKeys(), actual.repositoryKeys());
        if (expected instanceof CommitTarget) {
            CommitTarget left = (CommitTarget) expected;
            CommitTarget right = (CommitTarget) actual;
            assertEquals(left.getRepositoryKey(), right.getRepositoryKey());
            assertEquals(left.getBranch(), right.getBranch());
            assertEquals(left.getExpectedHead(), right.getExpectedHead());
            assertEquals(left.getExpectedStateHash(), right.getExpectedStateHash());
            assertEquals(left.getIncludedPaths(), right.getIncludedPaths());
            assertEquals(left.getMessageHash(), right.getMessageHash());
            assertEquals(left.getSafeMessagePreview(), right.getSafeMessagePreview());
        } else if (expected instanceof PushTarget) {
            PushTarget left = (PushTarget) expected;
            PushTarget right = (PushTarget) actual;
            assertEquals(left.getRepositoryKey(), right.getRepositoryKey());
            assertEquals(left.getRemoteName(), right.getRemoteName());
            assertEquals(left.getLocalBranch(), right.getLocalBranch());
            assertEquals(left.getRemoteRef(), right.getRemoteRef());
            assertEquals(left.getExpectedLocalHead(), right.getExpectedLocalHead());
        } else if (expected instanceof LocalDeployTarget) {
            LocalDeployTarget left = (LocalDeployTarget) expected;
            LocalDeployTarget right = (LocalDeployTarget) actual;
            assertEquals(left.getTemplateId(), right.getTemplateId());
            assertEquals(left.getTemplateVersion(), right.getTemplateVersion());
            assertEquals(left.getTemplateHash(), right.getTemplateHash());
            assertEquals(left.getRepositoryTargets(), right.getRepositoryTargets());
            assertEquals(left.getEnvironment(), right.getEnvironment());
            assertEquals(left.getExpectedWorkspaceStateHash(),
                    right.getExpectedWorkspaceStateHash());
            assertEquals(left.getRollbackSummary(), right.getRollbackSummary());
        } else {
            ProductionWriteTarget left = (ProductionWriteTarget) expected;
            ProductionWriteTarget right = (ProductionWriteTarget) actual;
            assertEquals(left.getEnvironment(), right.getEnvironment());
            assertEquals(left.getResourceReference(), right.getResourceReference());
            assertEquals(left.getExpectedProductionStateHash(),
                    right.getExpectedProductionStateHash());
        }
    }
}
