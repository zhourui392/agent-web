package com.example.agentweb.domain.chatrun;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-22T10:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-07-22T10:00:01Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-07-22T10:00:02Z");

    @Test
    void submit_should_create_pending_run_with_normalized_key() {
        ChatRun run = ChatRun.submit(ChatRunId.of("run-1"), "session-1", 11L, "  request-1  ", CREATED_AT);

        assertEquals("run-1", run.getId().getValue());
        assertEquals("session-1", run.getSessionId());
        assertEquals(11L, run.getUserMessageId());
        assertEquals("request-1", run.getIdempotencyKey());
        assertEquals(ChatRunStatus.PENDING, run.getStatus());
        assertEquals(0L, run.getLastEventSeq());
        assertEquals(CREATED_AT, run.getCreatedAt());
        assertFalse(run.isTerminal());
    }

    @Test
    void submit_should_reject_invalid_invariants() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatRun.submit(ChatRunId.of("run-1"), " ", 1L, "key", CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ChatRun.submit(ChatRunId.of("run-1"), "s1", 0L, "key", CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ChatRun.submit(ChatRunId.of("run-1"), "s1", 1L, " ", CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ChatRun.submit(ChatRunId.of("run-1"), "s1", 1L, repeat('x', 129), CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ChatRun.submit(ChatRunId.of("run-1"), "s1", 1L, "key", null));
    }

    @Test
    void lifecycle_should_support_success_and_keep_terminal_immutable() {
        ChatRun run = newRun();

        assertTrue(run.start(STARTED_AT));
        assertEquals(ChatRunStatus.RUNNING, run.getStatus());
        assertEquals(STARTED_AT, run.getStartedAt());

        assertTrue(run.succeed(21L, 0, FINISHED_AT));
        assertEquals(ChatRunStatus.SUCCEEDED, run.getStatus());
        assertEquals(Long.valueOf(21L), run.getAssistantMessageId());
        assertEquals(Integer.valueOf(0), run.getExitCode());
        assertEquals(FINISHED_AT, run.getFinishedAt());
        assertTrue(run.isTerminal());

        assertFalse(run.succeed(21L, 0, FINISHED_AT));
        assertThrows(IllegalChatRunTransitionException.class,
                () -> run.fail("EXECUTION_FAILED", "执行失败", 1, FINISHED_AT.plusSeconds(1)));
    }

    @Test
    void cancellation_should_be_idempotent_and_allow_completion_to_win_race() {
        ChatRun run = newRun();
        run.start(STARTED_AT);

        assertEquals(ChatRunCancellationDecision.REQUESTED, run.requestCancellation(FINISHED_AT));
        assertEquals(ChatRunStatus.CANCEL_REQUESTED, run.getStatus());
        assertEquals(ChatRunCancellationDecision.UNCHANGED,
                run.requestCancellation(FINISHED_AT.plusSeconds(1)));
        assertEquals(FINISHED_AT, run.getCancelRequestedAt());

        assertTrue(run.succeed(21L, 0, FINISHED_AT.plusSeconds(2)));
        assertEquals(ChatRunStatus.SUCCEEDED, run.getStatus());
    }

    @Test
    void cancel_before_spawn_should_enter_cancelled_directly() {
        ChatRun run = newRun();

        assertEquals(ChatRunCancellationDecision.CANCELLED_BEFORE_START,
                run.requestCancellation(STARTED_AT));

        assertEquals(ChatRunStatus.CANCELLED, run.getStatus());
        assertEquals(STARTED_AT, run.getFinishedAt());
        assertTrue(run.isTerminal());
    }

    @Test
    void failure_and_interruption_should_require_public_message() {
        ChatRun failed = newRun();
        failed.start(STARTED_AT);
        assertThrows(IllegalArgumentException.class,
                () -> failed.fail("EXECUTION_FAILED", " ", 1, FINISHED_AT));

        assertTrue(failed.fail("EXECUTION_FAILED", "执行失败，请稍后重试", 1, FINISHED_AT));
        assertEquals(ChatRunStatus.FAILED, failed.getStatus());
        assertEquals("EXECUTION_FAILED", failed.getFailureCode());
        assertEquals("执行失败，请稍后重试", failed.getErrorMessage());

        ChatRun interrupted = newRun();
        assertTrue(interrupted.interrupt("服务已重启，任务已中断", FINISHED_AT));
        assertEquals(ChatRunStatus.INTERRUPTED, interrupted.getStatus());
        assertNotNull(interrupted.getFinishedAt());
    }

    @Test
    void event_sequence_should_allocate_contiguous_ranges_monotonically() {
        ChatRun run = newRun();

        EventSequenceRange first = run.allocateEventSequence(3, STARTED_AT);
        EventSequenceRange second = run.allocateEventSequence(2, FINISHED_AT);

        assertEquals(1L, first.getStartInclusive());
        assertEquals(3L, first.getEndInclusive());
        assertEquals(4L, second.getStartInclusive());
        assertEquals(5L, second.getEndInclusive());
        assertEquals(5L, run.getLastEventSeq());
        assertThrows(IllegalArgumentException.class, () -> run.allocateEventSequence(0, FINISHED_AT));
    }

    @Test
    void illegal_transition_and_invalid_time_should_be_rejected() {
        ChatRun run = newRun();

        assertThrows(IllegalArgumentException.class, () -> run.start(CREATED_AT.minusSeconds(1)));
        assertThrows(IllegalChatRunTransitionException.class,
                () -> run.succeed(10L, 0, FINISHED_AT));

        run.start(STARTED_AT);
        assertThrows(IllegalArgumentException.class,
                () -> run.fail("FAILED", "失败", 1, CREATED_AT));
    }

    @Test
    void completion_decision_should_keep_execution_outcome_inside_aggregate() {
        ChatRun running = newRun();
        running.start(STARTED_AT);
        assertEquals(ChatRunCompletionDecision.SUCCEED, running.decideCompletion(0, true));
        assertEquals(ChatRunCompletionDecision.FAIL, running.decideCompletion(1, true));
        assertEquals(ChatRunCompletionDecision.FAIL, running.decideCompletion(0, false));

        ChatRun cancelling = newRun();
        cancelling.start(STARTED_AT);
        cancelling.requestCancellation(FINISHED_AT);
        assertEquals(ChatRunCompletionDecision.SUCCEED, cancelling.decideCompletion(0, true));
        assertEquals(ChatRunCompletionDecision.CANCEL, cancelling.decideCompletion(143, false));
    }

    private ChatRun newRun() {
        return ChatRun.submit(ChatRunId.of("run-1"), "session-1", 11L, "request-1", CREATED_AT);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
