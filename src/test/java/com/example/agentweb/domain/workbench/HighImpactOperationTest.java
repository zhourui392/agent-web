package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高影响操作的类型化 Target、独立授权、Preflight 与未知终态测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class HighImpactOperationTest {

    private static final Instant NOW = Instant.parse("2026-08-01T02:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");
    private static final HighImpactOperationPolicy POLICY =
            HighImpactOperationPolicy.withAuthorizationTtl(Duration.ofMinutes(15));

    @Test
    void fourOperationTypesShouldUseDedicatedTargetsAndPayloadHashes() {
        CommitTarget commit = commitTarget();
        PushTarget push = PushTarget.create(
                "agent-web", "origin", "master", "refs/heads/master",
                gitHead('b'));
        LocalDeployTarget deploy = LocalDeployTarget.create(
                "local-service", "1", repeat('c'),
                Collections.singletonList("agent-web"), repeat('d'),
                "停止本地服务并恢复旧进程");
        ProductionWriteTarget production = ProductionWriteTarget.describe(
                "production", "issue-log-index", repeat('e'));

        assertEquals(HighImpactOperationType.GIT_COMMIT, commit.getType());
        assertEquals(HighImpactOperationType.GIT_PUSH, push.getType());
        assertEquals(HighImpactOperationType.LOCAL_DEPLOY, deploy.getType());
        assertEquals(HighImpactOperationType.PRODUCTION_WRITE, production.getType());
        assertNotEquals(commit.requestedPayloadHash(), push.requestedPayloadHash());
        assertNotEquals(push.requestedPayloadHash(), deploy.requestedPayloadHash());
        assertFalse(push.isForceAllowed());
    }

    @Test
    void commitTargetShouldRequireExplicitUniquePathsInOneRepository() {
        assertThrows(IllegalArgumentException.class,
                () -> CommitTarget.create(
                        "agent-web", "master", gitHead('a'), repeat('b'),
                        Collections.<DocumentReference>emptyList(), repeat('c'), "message"));
        assertThrows(IllegalArgumentException.class,
                () -> CommitTarget.create(
                        "agent-web", "master", gitHead('a'), repeat('b'),
                        Arrays.asList(
                                DocumentReference.of("agent-web", "README.md"),
                                DocumentReference.of("shared-library", "README.md")),
                        repeat('c'), "message"));
        assertThrows(IllegalArgumentException.class,
                () -> CommitTarget.create(
                        "agent-web", "master", gitHead('a'), repeat('b'),
                        Arrays.asList(
                                DocumentReference.of("agent-web", "README.md"),
                                DocumentReference.of("agent-web", "README.md")),
                        repeat('c'), "message"));
    }

    @Test
    void proposalShouldRemainUnapprovedUntilExplicitOwnerDecision() {
        Workbench workbench = newWorkbench();
        HighImpactOperation operation = proposeCommit(workbench);

        assertEquals(HighImpactOperationStatus.PROPOSED, operation.getStatus());
        assertEquals(0L, operation.getVersion());
        assertThrows(WorkbenchDomainException.class,
                () -> POLICY.decide(
                        workbench, operation, OwnerReference.of("user-2", "Other"),
                        HighImpactOperationDecision.APPROVE,
                        "普通聊天里的同意不能授权", NOW.plusSeconds(1)));

        POLICY.decide(
                workbench, operation, OWNER, HighImpactOperationDecision.APPROVE,
                "已核对仓库、分支、状态和精确文件", NOW.plusSeconds(2));

        assertEquals(HighImpactOperationStatus.AUTHORIZED, operation.getStatus());
        assertEquals(OWNER, operation.getDecidedBy());
        assertEquals(1L, operation.getVersion());
        assertTrue(operation.getAuthorizationExpiresAt().isAfter(operation.getDecidedAt()));
        assertThrows(WorkbenchDomainException.class,
                () -> POLICY.decide(
                        workbench, operation, OWNER, HighImpactOperationDecision.APPROVE,
                        "不能重复授权", NOW.plusSeconds(3)));
    }

    @Test
    void proposalShouldRejectArchivedScopeEscapeAndCredentialSummary() {
        Workbench workbench = newWorkbench();
        CommitTarget outside = CommitTarget.create(
                "outside", "master", gitHead('a'), repeat('b'),
                Collections.singletonList(
                        DocumentReference.of("outside", "README.md")),
                repeat('c'), "feat: outside");
        assertThrows(IllegalArgumentException.class,
                () -> POLICY.propose(
                        workbench, "operation-outside",
                        WorkbenchRunReference.of(
                                "run-1", workbench.getId(),
                                WorkbenchPhase.IMPLEMENT_TEST, "开发完成"),
                        outside, "人工预览", OWNER, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> POLICY.propose(
                        workbench, "operation-secret",
                        WorkbenchRunReference.of(
                                "run-1", workbench.getId(),
                                WorkbenchPhase.IMPLEMENT_TEST, "开发完成"),
                        commitTarget(), "api_key=sk-super-secret-value",
                        OWNER, NOW));

        workbench.archive(OWNER, NOW.minusSeconds(1));
        WorkbenchDomainException archived = assertThrows(
                WorkbenchDomainException.class,
                () -> proposeCommit(workbench));
        assertEquals(WorkbenchErrorCode.ARCHIVED, archived.getCode());
    }

    @Test
    void decisionShouldRequireTheExactPersistedOperationVersion() {
        HighImpactOperation operation = proposeCommit(newWorkbench());

        operation.requireExpectedVersion(0L);

        WorkbenchDomainException stale = assertThrows(
                WorkbenchDomainException.class,
                () -> operation.requireExpectedVersion(1L));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, stale.getCode());
        assertThrows(IllegalArgumentException.class,
                () -> operation.requireExpectedVersion(-1L));
        assertEquals(HighImpactOperationStatus.PROPOSED, operation.getStatus());
        assertEquals(0L, operation.getVersion());
    }

    @Test
    void commitPushAndDeployAuthorizationsShouldNeverBeShared() {
        Workbench workbench = newWorkbench();
        HighImpactOperation commit = proposeCommit(workbench);
        HighImpactOperation push = POLICY.propose(
                workbench, "operation-push",
                WorkbenchRunReference.of(
                        "run-1", workbench.getId(), WorkbenchPhase.IMPLEMENT_TEST, "开发完成"),
                PushTarget.create("agent-web", "origin", "master",
                        "refs/heads/master", gitHead('a')),
                "Push master", OWNER, NOW);

        POLICY.decide(workbench, commit, OWNER, HighImpactOperationDecision.APPROVE,
                "只批准 commit", NOW.plusSeconds(1));

        assertEquals(HighImpactOperationStatus.AUTHORIZED, commit.getStatus());
        assertEquals(HighImpactOperationStatus.PROPOSED, push.getStatus());
        assertNotEquals(commit.getRequestedPayloadHash(), push.getRequestedPayloadHash());
    }

    @Test
    void targetStateChangeShouldExpireAuthorizationAndRejectPermit() {
        Workbench workbench = newWorkbench();
        HighImpactOperation operation = proposeCommit(workbench);
        POLICY.decide(workbench, operation, OWNER, HighImpactOperationDecision.APPROVE,
                "批准 commit", NOW.plusSeconds(1));

        WorkbenchDomainException changed = assertThrows(
                WorkbenchDomainException.class,
                () -> POLICY.issueExecutionPermit(
                        workbench, operation, OWNER,
                        HighImpactPreflightProof.verified(
                                operation.getRequestedPayloadHash(), repeat('f'), repeat('1'),
                                NOW.plusSeconds(2)),
                        true, "permit-1", NOW.plusSeconds(3)));

        assertEquals(WorkbenchErrorCode.OPERATION_TARGET_CHANGED, changed.getCode());
        assertEquals(HighImpactOperationStatus.EXPIRED, operation.getStatus());
    }

    @Test
    void closedExecutorAndProductionWriteShouldFailClosed() {
        Workbench workbench = newWorkbench();
        HighImpactOperation commit = proposeCommit(workbench);
        POLICY.decide(workbench, commit, OWNER, HighImpactOperationDecision.APPROVE,
                "批准 commit", NOW.plusSeconds(1));
        HighImpactPreflightProof commitProof = HighImpactPreflightProof.verified(
                commit.getRequestedPayloadHash(), commit.getTarget().expectedStateBinding(),
                repeat('1'), NOW.plusSeconds(2));

        assertThrows(WorkbenchDomainException.class,
                () -> POLICY.issueExecutionPermit(
                        workbench, commit, OWNER, commitProof,
                        false, "permit-1", NOW.plusSeconds(3)));
        assertEquals(HighImpactOperationStatus.AUTHORIZED, commit.getStatus());

        HighImpactOperation production = POLICY.propose(
                workbench, "operation-production",
                WorkbenchRunReference.of(
                        "run-1", workbench.getId(), WorkbenchPhase.IMPLEMENT_TEST, "开发完成"),
                ProductionWriteTarget.describe(
                        "production", "database/orders", repeat('2')),
                "生产写入", OWNER, NOW);
        POLICY.decide(workbench, production, OWNER, HighImpactOperationDecision.APPROVE,
                "仅形成授权事实", NOW.plusSeconds(1));
        HighImpactPreflightProof productionProof = HighImpactPreflightProof.verified(
                production.getRequestedPayloadHash(),
                production.getTarget().expectedStateBinding(), repeat('3'),
                NOW.plusSeconds(2));
        assertThrows(WorkbenchDomainException.class,
                () -> POLICY.issueExecutionPermit(
                        workbench, production, OWNER, productionProof,
                        true, "permit-production", NOW.plusSeconds(3)));
        assertEquals(HighImpactOperationStatus.AUTHORIZED, production.getStatus());
    }

    @Test
    void unknownTerminalShouldRequireReconciliationAndForbidReplay() {
        Workbench workbench = newWorkbench();
        HighImpactOperation operation = proposeCommit(workbench);
        POLICY.decide(workbench, operation, OWNER, HighImpactOperationDecision.APPROVE,
                "批准 commit", NOW.plusSeconds(1));
        HighImpactPreflightProof proof = HighImpactPreflightProof.verified(
                operation.getRequestedPayloadHash(),
                operation.getTarget().expectedStateBinding(), repeat('1'),
                NOW.plusSeconds(2));
        OperationExecutionPermit permit = POLICY.issueExecutionPermit(
                workbench, operation, OWNER, proof,
                true, "permit-1", NOW.plusSeconds(3));
        operation.startExecution(permit, "execution-1", NOW.plusSeconds(4));
        operation.requireReconciliation("UNKNOWN_TERMINAL", NOW.plusSeconds(5));

        assertEquals(HighImpactOperationStatus.RECONCILIATION_REQUIRED,
                operation.getStatus());
        assertThrows(WorkbenchDomainException.class,
                () -> operation.startExecution(
                        permit, "execution-2", NOW.plusSeconds(6)));
    }

    @Test
    void restoreShouldRehydrateAuthorizedOperationWithoutReplayingTransitions() {
        CommitTarget target = commitTarget();
        WorkbenchRunReference sourceRun = WorkbenchRunReference.of(
                "run-1", WorkbenchId.of("workbench-1"),
                WorkbenchPhase.IMPLEMENT_TEST, "开发完成");

        HighImpactOperation restored = HighImpactOperation.restore(
                "operation-restored", WorkbenchId.of("workbench-1"), sourceRun,
                target, target.requestedPayloadHash(), "Commit 精确文件",
                HighImpactOperationStatus.AUTHORIZED,
                OWNER, NOW, OWNER, "已核对目标",
                NOW.plusSeconds(1), NOW.plusSeconds(901),
                null, null, null, NOW.plusSeconds(1), 7L);

        assertEquals(HighImpactOperationStatus.AUTHORIZED, restored.getStatus());
        assertEquals(7L, restored.getVersion());
        assertEquals(NOW.plusSeconds(901), restored.getAuthorizationExpiresAt());
        assertEquals(target.requestedPayloadHash(), restored.getRequestedPayloadHash());
    }

    @Test
    void restoreShouldFailFastOnStateSpecificPersistenceCorruption() {
        CommitTarget target = commitTarget();
        WorkbenchRunReference sourceRun = WorkbenchRunReference.of(
                "run-1", WorkbenchId.of("workbench-1"),
                WorkbenchPhase.IMPLEMENT_TEST, "开发完成");

        assertThrows(IllegalArgumentException.class,
                () -> HighImpactOperation.restore(
                        "operation-corrupt", WorkbenchId.of("workbench-1"), sourceRun,
                        target, target.requestedPayloadHash(), "Commit 精确文件",
                        HighImpactOperationStatus.EXECUTING,
                        OWNER, NOW, OWNER, "已核对目标",
                        NOW.plusSeconds(1), NOW.plusSeconds(901),
                        null, null, null, NOW.plusSeconds(2), 3L));
        assertThrows(IllegalArgumentException.class,
                () -> HighImpactOperation.restore(
                        "operation-corrupt-2", WorkbenchId.of("workbench-1"), sourceRun,
                        target, repeat('f'), "Commit 精确文件",
                        HighImpactOperationStatus.AUTHORIZED,
                        OWNER, NOW, OWNER, "已核对目标",
                        NOW.plusSeconds(1), NOW.plusSeconds(901),
                        null, null, null, NOW.plusSeconds(1), 3L));
    }

    private static HighImpactOperation proposeCommit(Workbench workbench) {
        return POLICY.propose(
                workbench, "operation-commit",
                WorkbenchRunReference.of(
                        "run-1", workbench.getId(), WorkbenchPhase.IMPLEMENT_TEST, "开发完成"),
                commitTarget(), "Commit 精确文件", OWNER, NOW);
    }

    private static CommitTarget commitTarget() {
        return CommitTarget.create(
                "agent-web", "master", gitHead('a'), repeat('b'),
                Arrays.asList(
                        DocumentReference.of("agent-web", "README.md"),
                        DocumentReference.of("agent-web", "src/App.java")),
                repeat('c'), "feat: workbench");
    }

    private static Workbench newWorkbench() {
        RepositoryScope repositoryScope = WorkbenchDomainFixtures.repositoryScope();
        return Workbench.create(
                WorkbenchId.of("workbench-1"), OWNER,
                "Workbench MVP", "实现本地开发工作台", AgentType.CODEX, "local",
                repositoryScope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('0')),
                NOW.minusSeconds(10));
    }

    private static String gitHead(char value) {
        StringBuilder result = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
