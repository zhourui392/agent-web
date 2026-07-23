package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeExecution 生命周期、事件幂等及取消优先语义测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class RuntimeExecutionTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void shouldMoveFromPreparedToSucceededAndIgnoreDuplicateEventSequence() {
        RuntimeExecution execution = execution();

        execution.markStarting(NOW.plusSeconds(1));
        assertTrue(execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "pid-1",
                NOW.plusSeconds(2))));
        assertFalse(execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "pid-1",
                NOW.plusSeconds(2))));
        assertTrue(execution.apply(RuntimeExecutionSignal.succeeded(2L, 0, "result-artifact",
                true, NOW.plusSeconds(3))));

        assertEquals(RuntimeExecutionStatus.SUCCEEDED, execution.getStatus());
        assertEquals(2L, execution.getLastEventSequence());
        assertEquals(RuntimeCleanupStatus.SUCCEEDED, execution.getCleanupStatus());
        assertThrows(IllegalHarnessTransitionException.class,
                () -> execution.markStarting(NOW.plusSeconds(4)));
    }

    @Test
    void successfulExitAfterCancellationIntentShouldStillBecomeCancelled() {
        RuntimeExecution execution = execution();
        execution.markStarting(NOW.plusSeconds(1));
        execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "pid-1",
                NOW.plusSeconds(2)));
        execution.requestCancellation("admin", "stop", NOW.plusSeconds(3));

        execution.apply(RuntimeExecutionSignal.succeeded(2L, 0, "result-artifact",
                true, NOW.plusSeconds(4)));

        assertEquals(RuntimeExecutionStatus.CANCELLED, execution.getStatus());
        assertEquals("stop", execution.getTerminationReason());
    }

    @Test
    void outputAlreadyInPipeShouldRemainAcceptableAfterCancellationIntent() {
        RuntimeExecution execution = execution();
        execution.markStarting(NOW.plusSeconds(1));
        execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "pid-1",
                NOW.plusSeconds(2)));
        execution.requestCancellation("admin", "stop", NOW.plusSeconds(3));

        assertTrue(execution.apply(RuntimeExecutionSignal.output(2L, null,
                NOW.plusSeconds(4))));
        execution.apply(RuntimeExecutionSignal.cancelled(3L, 0, "stopped",
                "artifact:runtime", true, NOW.plusSeconds(5)));

        assertEquals(RuntimeExecutionStatus.CANCELLED, execution.getStatus());
        assertEquals("artifact:runtime", execution.getEvidenceReference());
    }

    @Test
    void activeExecutionCanTimeOutOrBecomeLostButCannotRestart() {
        RuntimeExecution timedOut = execution();
        timedOut.markStarting(NOW.plusSeconds(1));
        timedOut.apply(RuntimeExecutionSignal.started(1L, "codex-test", "pid-1",
                NOW.plusSeconds(2)));
        timedOut.apply(RuntimeExecutionSignal.timedOut(2L, "runtime timeout", false,
                NOW.plusSeconds(3)));

        RuntimeExecution lost = RuntimeExecution.prepare("exec-2", "launch-2", permit(),
                AgentRuntime.CODEX, NOW);
        lost.markStarting(NOW.plusSeconds(1));
        lost.apply(RuntimeExecutionSignal.lost(1L, "restart reconciliation failed",
                NOW.plusSeconds(2)));

        assertEquals(RuntimeExecutionStatus.TIMED_OUT, timedOut.getStatus());
        assertEquals(RuntimeExecutionStatus.LOST, lost.getStatus());
        assertThrows(IllegalHarnessTransitionException.class,
                () -> lost.markStarting(NOW.plusSeconds(3)));
    }

    @Test
    void idempotentStartMustKeepOriginalRunAndStageSemantics() {
        RuntimeExecution execution = execution();

        execution.requireSameStartRequest("run-1", HarnessStage.ANALYSIS);

        assertThrows(RuntimeExecutionIdempotencyConflictException.class,
                () -> execution.requireSameStartRequest("run-1", HarnessStage.DESIGN));
        assertThrows(RuntimeExecutionIdempotencyConflictException.class,
                () -> execution.requireSameStartRequest("run-2", HarnessStage.ANALYSIS));
    }

    private RuntimeExecution execution() {
        return RuntimeExecution.prepare("exec-1", "launch-1", permit(), AgentRuntime.CODEX, NOW);
    }

    private ExecutionPermit permit() {
        return new ExecutionPermit("run-1", HarnessStage.ANALYSIS, 1,
                hash('a'), hash('b'), Collections.singleton("reader"));
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
