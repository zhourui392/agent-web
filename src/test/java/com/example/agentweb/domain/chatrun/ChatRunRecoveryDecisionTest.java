package com.example.agentweb.domain.chatrun;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRun 重启恢复决策测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatRunRecoveryDecisionTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-01T18:00:00Z");

    @Test
    void pendingRunShouldNeverBeAdoptedAsRunningAfterRestart() {
        ChatRun run = pending("pending-run");

        assertEquals(ChatRunRecoveryDecision.STOP_AND_INTERRUPT,
                run.decideRecovery(ChatRunRecoveryLiveness.ALIVE));
        assertEquals(ChatRunRecoveryDecision.INTERRUPT,
                run.decideRecovery(ChatRunRecoveryLiveness.TERMINATED));
        assertEquals(ChatRunRecoveryDecision.INTERRUPT,
                run.decideRecovery(ChatRunRecoveryLiveness.UNAVAILABLE));
    }

    @Test
    void runningRunShouldRemainActiveOnlyWhileRuntimeIsProvablyAlive() {
        ChatRun run = pending("running-run");
        run.start(CREATED_AT.plusSeconds(1));

        assertEquals(ChatRunRecoveryDecision.RETAIN_ACTIVE,
                run.decideRecovery(ChatRunRecoveryLiveness.ALIVE));
        assertEquals(ChatRunRecoveryDecision.FINALIZE_TERMINATION,
                run.decideRecovery(ChatRunRecoveryLiveness.TERMINATED));
        assertEquals(ChatRunRecoveryDecision.INTERRUPT,
                run.decideRecovery(ChatRunRecoveryLiveness.UNAVAILABLE));
    }

    @Test
    void cancellationIntentShouldBeRetainedAndReconciledWithoutReplay() {
        ChatRun run = pending("cancel-run");
        run.start(CREATED_AT.plusSeconds(1));
        run.requestCancellation(CREATED_AT.plusSeconds(2));

        assertEquals(ChatRunRecoveryDecision.RETAIN_ACTIVE,
                run.decideRecovery(ChatRunRecoveryLiveness.ALIVE));
        assertEquals(ChatRunRecoveryDecision.FINALIZE_TERMINATION,
                run.decideRecovery(ChatRunRecoveryLiveness.TERMINATED));
        assertEquals(ChatRunRecoveryDecision.INTERRUPT,
                run.decideRecovery(ChatRunRecoveryLiveness.UNAVAILABLE));
    }

    @Test
    void alreadyTerminalRunShouldBeIgnoredWhenActiveQueryWasStale() {
        ChatRun run = pending("terminal-run");
        run.requestCancellation(CREATED_AT.plusSeconds(1));

        assertEquals(ChatRunRecoveryDecision.IGNORE_TERMINAL,
                run.decideRecovery(ChatRunRecoveryLiveness.ALIVE));
        assertEquals(ChatRunRecoveryDecision.IGNORE_TERMINAL,
                run.decideRecovery(ChatRunRecoveryLiveness.TERMINATED));
        assertEquals(ChatRunRecoveryDecision.IGNORE_TERMINAL,
                run.decideRecovery(ChatRunRecoveryLiveness.UNAVAILABLE));
    }

    @Test
    void decisionShouldExposeWhetherRecoveryAppliesPersistentSideEffects() {
        assertFalse(ChatRunRecoveryDecision.RETAIN_ACTIVE
                .isRecoveryApplied());
        assertFalse(ChatRunRecoveryDecision.IGNORE_TERMINAL
                .isRecoveryApplied());
        assertTrue(ChatRunRecoveryDecision.FINALIZE_TERMINATION
                .isRecoveryApplied());
        assertTrue(ChatRunRecoveryDecision.STOP_AND_INTERRUPT
                .isRecoveryApplied());
        assertTrue(ChatRunRecoveryDecision.INTERRUPT
                .isRecoveryApplied());
    }

    private static ChatRun pending(String runId) {
        return ChatRun.submit(
                ChatRunId.of(runId), "session-" + runId, 1L,
                "key-" + runId, CREATED_AT);
    }
}
