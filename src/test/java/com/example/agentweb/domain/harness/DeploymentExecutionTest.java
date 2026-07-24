package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本机部署执行的幂等、Git Preflight、终态和重启对账测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class DeploymentExecutionTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void shouldPrepareAndRunOnlyWhenWorkspaceStillMatchesPermit() {
        WorkspaceBaseline baseline = baseline('a');
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                permit(baseline), template(), NOW);

        assertTrue(execution.begin(baseline, NOW.plusSeconds(1)));
        execution.succeed(NOW.plusSeconds(2));

        assertEquals(DeploymentExecutionStatus.SUCCEEDED, execution.getStatus());
        assertFalse(execution.begin(baseline, NOW.plusSeconds(3)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> execution.fail("late failure", NOW.plusSeconds(4)));
    }

    @Test
    void changedWorkspaceShouldFailPreflightWithoutStarting() {
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                permit(baseline('a')), template(), NOW);

        assertFalse(execution.begin(baseline('b'), NOW.plusSeconds(1)));

        assertEquals(DeploymentExecutionStatus.FAILED, execution.getStatus());
        assertEquals("deployment workspace baseline changed", execution.getFailureReason());
    }

    @Test
    void terminalExecutionShouldExposeImmutableOutcomeInsteadOfLeakingAggregate() {
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                permit(baseline('a')), template(), NOW);
        execution.begin(baseline('a'), NOW.plusSeconds(1));
        execution.succeed(NOW.plusSeconds(2));

        DeploymentExecutionOutcome outcome = execution.outcome()
                .orElseThrow(IllegalStateException::new);

        assertEquals("deploy-1", outcome.getExecutionId());
        assertEquals("run-1", outcome.getRunId());
        assertEquals(DeploymentExecutionStatus.SUCCEEDED, outcome.getStatus());
        assertTrue(outcome.isSuccessful());
    }

    @Test
    void idempotencyKeyMustNotBeReusedForAnotherBaselineOrTemplate() {
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                permit(baseline('a')), template(), NOW);

        execution.requireSameRequest("run-1", hash('c'), template());
        assertThrows(DeploymentExecutionIdempotencyConflictException.class,
                () -> execution.requireSameRequest("run-1", hash('d'), template()));
        assertThrows(DeploymentExecutionIdempotencyConflictException.class,
                () -> execution.requireSameRequest("run-1", hash('c'),
                        new DeploymentTemplateReference("other", "1", hash('e'), true)));
    }

    @Test
    void unfinishedExecutionShouldRequireManualReconciliationAfterRestart() {
        DeploymentExecution prepared = DeploymentExecution.prepare("deploy-1", "key-1",
                permit(baseline('a')), template(), NOW);
        DeploymentExecution running = DeploymentExecution.prepare("deploy-2", "key-2",
                permit(baseline('a')), template(), NOW);
        running.begin(baseline('a'), NOW.plusSeconds(1));

        assertTrue(prepared.requireReconciliation("application restarted", NOW.plusSeconds(2)));
        assertTrue(running.requireReconciliation("application restarted", NOW.plusSeconds(2)));
        assertEquals(DeploymentExecutionStatus.RECONCILIATION_REQUIRED, prepared.getStatus());
        assertEquals(DeploymentExecutionStatus.RECONCILIATION_REQUIRED, running.getStatus());
        assertFalse(running.requireReconciliation("again", NOW.plusSeconds(3)));
    }

    @Test
    void onlyReconciliationRequiredExecutionCanBeManuallyClosedAsFailed() {
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                permit(baseline('a')), template(), NOW);

        assertThrows(IllegalHarnessTransitionException.class,
                () -> execution.reconcileAsFailed("not uncertain", NOW.plusSeconds(1)));

        execution.requireReconciliation("application restarted", NOW.plusSeconds(2));
        execution.reconcileAsFailed("verified deployment failed", NOW.plusSeconds(3));

        assertEquals(DeploymentExecutionStatus.FAILED, execution.getStatus());
        assertEquals("verified deployment failed", execution.getFailureReason());
        assertEquals(NOW.plusSeconds(3), execution.getFinishedAt());
    }

    private DeploymentPermit permit(WorkspaceBaseline baseline) {
        return new DeploymentPermit("run-1", 1, hash('c'), baseline);
    }

    private DeploymentTemplateReference template() {
        return new DeploymentTemplateReference("local-default", "1.0.0", hash('f'), true);
    }

    private WorkspaceBaseline baseline(char diff) {
        return WorkspaceBaseline.capture("/workspace", "feat/m4",
                "0123456789012345678901234567890123456789", false, hash(diff), NOW);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
