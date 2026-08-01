package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 单个 ExecutionContext 内工具生命周期的单调耗时跟踪测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeToolTimingTrackerTest {

    @Test
    void sameExecutionAndCallIdShouldUseObservedMonotonicDifference() {
        RuntimeToolTimingTracker tracker = new RuntimeToolTimingTracker(
                "exec-1", times(10_000_000L, 27_000_000L));

        RuntimeEvent started = tracker.enhance(event(
                "exec-1", 1L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        RuntimeEvent finished = tracker.enhance(event(
                "exec-1", 2L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        assertFalse(semantic(started).getData().containsKey("durationMs"));
        assertEquals(17L, semantic(finished).getData().get("durationMs"));
    }

    @Test
    void missingStartDifferentCallAndDifferentExecutionShouldOmitDuration() {
        RuntimeToolTimingTracker firstExecution =
                new RuntimeToolTimingTracker(
                        "exec-1", times(1_000_000L, 9_000_000L));
        RuntimeToolTimingTracker secondExecution =
                new RuntimeToolTimingTracker(
                        "exec-2", times(20_000_000L));

        firstExecution.enhance(event(
                "exec-1", 1L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        RuntimeEvent differentCall = firstExecution.enhance(event(
                "exec-1", 2L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-2", "SUCCEEDED")));
        RuntimeEvent differentExecution = secondExecution.enhance(event(
                "exec-2", 1L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        assertFalse(semantic(differentCall).getData()
                .containsKey("durationMs"));
        assertFalse(semantic(differentExecution).getData()
                .containsKey("durationMs"));
    }

    @Test
    void duplicateAndOutOfOrderLifecycleShouldNotForgeDuration() {
        AtomicLong duplicateTime = new AtomicLong(1_000_000L);
        RuntimeToolTimingTracker duplicateLifecycle =
                new RuntimeToolTimingTracker(
                        "exec-1", duplicateTime::get);
        duplicateLifecycle.enhance(event(
                "exec-1", 1L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        duplicateTime.set(4_000_000L);
        duplicateLifecycle.enhance(event(
                "exec-1", 2L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        duplicateTime.set(11_000_000L);
        RuntimeEvent duplicateStartFinished = duplicateLifecycle.enhance(event(
                "exec-1", 3L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        AtomicLong outOfOrderTime = new AtomicLong(10_000_000L);
        RuntimeToolTimingTracker outOfOrderLifecycle =
                new RuntimeToolTimingTracker(
                        "exec-2", outOfOrderTime::get);
        RuntimeEvent orphanFinished = outOfOrderLifecycle.enhance(event(
                "exec-2", 1L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));
        outOfOrderLifecycle.enhance(event(
                "exec-2", 2L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        outOfOrderTime.set(20_000_000L);
        RuntimeEvent repeatedFinished = outOfOrderLifecycle.enhance(event(
                "exec-2", 3L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        RuntimeToolTimingTracker duplicateFinishLifecycle =
                new RuntimeToolTimingTracker(
                        "exec-3", times(1_000_000L, 3_000_000L));
        duplicateFinishLifecycle.enhance(event(
                "exec-3", 1L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        RuntimeEvent firstFinished = duplicateFinishLifecycle.enhance(event(
                "exec-3", 2L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));
        RuntimeEvent duplicateFinished = duplicateFinishLifecycle.enhance(event(
                "exec-3", 3L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        assertFalse(semantic(duplicateStartFinished).getData()
                .containsKey("durationMs"));
        assertFalse(semantic(orphanFinished).getData()
                .containsKey("durationMs"));
        assertFalse(semantic(repeatedFinished).getData()
                .containsKey("durationMs"));
        assertEquals(2L, semantic(firstFinished)
                .getData().get("durationMs"));
        assertFalse(semantic(duplicateFinished).getData()
                .containsKey("durationMs"));
    }

    @Test
    void releasedNonMonotonicOrExcessiveObservationShouldOmitDuration() {
        RuntimeToolTimingTracker released = new RuntimeToolTimingTracker(
                "exec-1", times(1_000_000L, 2_000_000L));
        released.enhance(event(
                "exec-1", 1L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        released.clear();
        RuntimeEvent afterRelease = released.enhance(event(
                "exec-1", 2L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        RuntimeToolTimingTracker nonMonotonic =
                new RuntimeToolTimingTracker(
                        "exec-2", times(5_000_000L, 4_000_000L));
        nonMonotonic.enhance(event(
                "exec-2", 1L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        RuntimeEvent regressed = nonMonotonic.enhance(event(
                "exec-2", 2L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        long beyondBound = TimeUnit.MILLISECONDS.toNanos(
                RuntimeSemanticEvent.MAX_TOOL_DURATION_MILLIS + 1L);
        RuntimeToolTimingTracker excessive =
                new RuntimeToolTimingTracker(
                        "exec-3", times(0L, beyondBound));
        excessive.enhance(event(
                "exec-3", 1L, RuntimeSemanticEvent.toolStarted(
                        "repository/read", "call-1", "RUNNING")));
        RuntimeEvent tooLong = excessive.enhance(event(
                "exec-3", 2L, RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")));

        assertFalse(semantic(afterRelease).getData()
                .containsKey("durationMs"));
        assertFalse(semantic(regressed).getData()
                .containsKey("durationMs"));
        assertFalse(semantic(tooLong).getData()
                .containsKey("durationMs"));
    }

    private static RuntimeEvent event(
            String executionId, long sequence,
            RuntimeSemanticEvent semanticEvent) {
        return new RuntimeEvent(
                executionId, sequence, RuntimeEventType.OUTPUT,
                "safe provider event", null,
                Collections.singletonList(semanticEvent));
    }

    private static RuntimeSemanticEvent semantic(RuntimeEvent event) {
        return event.getSemanticEvents().get(0);
    }

    private static LongSupplier times(long... observations) {
        AtomicInteger index = new AtomicInteger();
        return () -> observations[Math.min(
                index.getAndIncrement(), observations.length - 1)];
    }
}
