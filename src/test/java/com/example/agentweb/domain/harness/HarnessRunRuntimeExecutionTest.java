package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HarnessRun 对 Attempt、Snapshot、Execution 与取消一致性边界的测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class HarnessRunRuntimeExecutionTest {

    private static final Instant NOW = Instant.parse("2026-07-23T13:00:00Z");

    private HarnessRun run;

    @BeforeEach
    void setUp() {
        run = HarnessRun.create("run-1", "M3", "/workspace", "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", StageContract.mvpDefaults(), NOW);
        run.startStage(HarnessStage.ANALYSIS, "start-1", NOW.plusSeconds(1));
    }

    @Test
    void shouldBindOneSnapshotAndOneExecutionToCurrentAttempt() {
        ExecutionPermit permit = run.authorizeExecution(HarnessStage.ANALYSIS, snapshotReference());
        ExecutionReference reference = new ExecutionReference("exec-1", "run-1",
                HarnessStage.ANALYSIS, 1, hash('a'));

        run.bindExecution(reference, NOW.plusSeconds(2));

        assertEquals(hash('a'), run.stage(HarnessStage.ANALYSIS)
                .currentAttempt().getSnapshotHash());
        assertEquals("exec-1", run.stage(HarnessStage.ANALYSIS)
                .currentAttempt().getExecutionId());
        assertEquals(hash('b'), permit.getPromptHash());
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.bindExecution(new ExecutionReference("exec-2", "run-1",
                        HarnessStage.ANALYSIS, 1, hash('a')), NOW.plusSeconds(3)));
    }

    @Test
    void cancellationWithActiveExecutionShouldPersistIntentBeforeFinalCancellation() {
        run.authorizeExecution(HarnessStage.ANALYSIS, snapshotReference());
        ExecutionReference reference = new ExecutionReference("exec-1", "run-1",
                HarnessStage.ANALYSIS, 1, hash('a'));
        run.bindExecution(reference, NOW.plusSeconds(2));

        CancellationDirective directive = run.requestCancellation(
                "admin", "stop", NOW.plusSeconds(3));

        assertTrue(directive.requiresRuntimeCancellation());
        assertEquals("exec-1", directive.getExecutionId());
        assertEquals(HarnessRunStatus.CANCELLING, run.getStatus());
        assertEquals(StageStatus.CANCELLING,
                run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.CANCELLING,
                run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());

        run.confirmCancellation(reference, NOW.plusSeconds(4));

        assertEquals(HarnessRunStatus.CANCELLED, run.getStatus());
        assertEquals(StageAttemptStatus.CANCELLED,
                run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
    }

    @Test
    void snapshotFromAnotherAttemptShouldNotAuthorizeExecution() {
        CapabilitySnapshotReference wrong = new CapabilitySnapshotReference("run-1",
                HarnessStage.ANALYSIS, 2, hash('a'), hash('b'), Collections.singleton("reader"));

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.authorizeExecution(HarnessStage.ANALYSIS, wrong));
    }

    @Test
    void runtimeTerminalOutcomeShouldBeAppliedByRunAggregate() {
        ExecutionPermit permit = run.authorizeExecution(HarnessStage.ANALYSIS, snapshotReference());
        RuntimeExecution execution = RuntimeExecution.prepare(
                "exec-1", "launch-1", permit, AgentRuntime.CODEX, NOW.plusSeconds(2));
        run.bindExecution(execution.reference(), NOW.plusSeconds(2));
        execution.markStarting(NOW.plusSeconds(3));
        execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "pid-1",
                NOW.plusSeconds(4)));
        execution.apply(RuntimeExecutionSignal.failed(2L, 1, "agent failed", true,
                NOW.plusSeconds(5)));

        assertTrue(run.applyRuntimeExecutionOutcome(execution, NOW.plusSeconds(5)));

        assertEquals(HarnessRunStatus.FAILED, run.getStatus());
        assertEquals(StageAttemptStatus.FAILED,
                run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
    }

    private CapabilitySnapshotReference snapshotReference() {
        return new CapabilitySnapshotReference("run-1", HarnessStage.ANALYSIS, 1,
                hash('a'), hash('b'), Collections.singleton("reader"));
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
