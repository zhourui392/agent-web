package com.example.agentweb.infra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class StreamProcessWatchdogTest {

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void activity_should_reschedule_idle_timeout() throws Exception {
        CountDownLatch timedOut = new CountDownLatch(1);
        AtomicReference<StreamProcessWatchdog.TimeoutReason> reason =
                new AtomicReference<StreamProcessWatchdog.TimeoutReason>();
        StreamProcessWatchdog watchdog = new StreamProcessWatchdog(
                scheduler, Duration.ofMillis(160L), Duration.ofSeconds(2L),
                StreamProcessWatchdog.TimeoutReason.MAX_RUNTIME,
                value -> {
                    reason.set(value);
                    timedOut.countDown();
                });

        assertFalse(timedOut.await(100L, TimeUnit.MILLISECONDS));
        watchdog.recordActivity();
        assertFalse(timedOut.await(100L, TimeUnit.MILLISECONDS),
                "stdout activity should renew the idle deadline");
        assertTrue(timedOut.await(200L, TimeUnit.MILLISECONDS));
        assertEquals(StreamProcessWatchdog.TimeoutReason.IDLE, reason.get());
        watchdog.close();
    }

    @Test
    void max_runtime_should_not_be_renewed_by_activity() throws Exception {
        CountDownLatch timedOut = new CountDownLatch(1);
        AtomicReference<StreamProcessWatchdog.TimeoutReason> reason =
                new AtomicReference<StreamProcessWatchdog.TimeoutReason>();
        StreamProcessWatchdog watchdog = new StreamProcessWatchdog(
                scheduler, Duration.ofMillis(700L), Duration.ofMillis(800L),
                StreamProcessWatchdog.TimeoutReason.MAX_RUNTIME,
                value -> {
                    reason.set(value);
                    timedOut.countDown();
                });

        // 并行测试负载下给绝对期限保留充足余量；最后一次活动后的 idle 期限仍晚于绝对期限。
        // 若实现错误地续期绝对期限，则会先收到 IDLE，下面的原因断言仍能识别该回归。
        for (int i = 0; i < 3; i++) {
            assertFalse(timedOut.await(50L, TimeUnit.MILLISECONDS));
            watchdog.recordActivity();
        }

        assertTrue(timedOut.await(900L, TimeUnit.MILLISECONDS));
        assertEquals(StreamProcessWatchdog.TimeoutReason.MAX_RUNTIME, reason.get());
        watchdog.close();
    }

    @Test
    void explicit_hard_timeout_should_report_hard_timeout_reason() throws Exception {
        CountDownLatch timedOut = new CountDownLatch(1);
        AtomicReference<StreamProcessWatchdog.TimeoutReason> reason =
                new AtomicReference<StreamProcessWatchdog.TimeoutReason>();
        StreamProcessWatchdog watchdog = new StreamProcessWatchdog(
                scheduler, Duration.ZERO, Duration.ofMillis(80L),
                StreamProcessWatchdog.TimeoutReason.HARD_TIMEOUT,
                value -> {
                    reason.set(value);
                    timedOut.countDown();
                });

        watchdog.recordActivity();

        assertTrue(timedOut.await(250L, TimeUnit.MILLISECONDS));
        assertEquals(StreamProcessWatchdog.TimeoutReason.HARD_TIMEOUT, reason.get());
        watchdog.close();
    }

    @Test
    void close_should_cancel_all_deadlines() throws Exception {
        CountDownLatch timedOut = new CountDownLatch(1);
        StreamProcessWatchdog watchdog = new StreamProcessWatchdog(
                scheduler, Duration.ofMillis(60L), Duration.ofMillis(80L),
                StreamProcessWatchdog.TimeoutReason.MAX_RUNTIME,
                value -> timedOut.countDown());

        watchdog.close();

        assertFalse(timedOut.await(180L, TimeUnit.MILLISECONDS));
    }
}
