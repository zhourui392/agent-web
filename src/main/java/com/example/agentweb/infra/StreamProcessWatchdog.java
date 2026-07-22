package com.example.agentweb.infra;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式 CLI 进程的技术超时控制器。
 * <p>空闲期限会被 stdout 活动续期，绝对期限从创建后固定不变；任一期限触发或主动关闭后，
 * 其余任务都会取消，超时回调至多执行一次。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
final class StreamProcessWatchdog implements AutoCloseable {

    enum TimeoutReason {
        IDLE,
        MAX_RUNTIME,
        HARD_TIMEOUT
    }

    private final ScheduledExecutorService scheduler;
    private final Duration idleTimeout;
    private final Consumer<TimeoutReason> timeoutHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object deadlineMonitor = new Object();
    private ScheduledFuture<?> idleDeadline;
    private ScheduledFuture<?> absoluteDeadline;

    StreamProcessWatchdog(ScheduledExecutorService scheduler,
                          Duration idleTimeout,
                          Duration absoluteTimeout,
                          TimeoutReason absoluteTimeoutReason,
                          Consumer<TimeoutReason> timeoutHandler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.timeoutHandler = Objects.requireNonNull(timeoutHandler, "timeoutHandler");
        Objects.requireNonNull(absoluteTimeout, "absoluteTimeout");
        Objects.requireNonNull(absoluteTimeoutReason, "absoluteTimeoutReason");
        synchronized (deadlineMonitor) {
            idleDeadline = schedule(idleTimeout, TimeoutReason.IDLE);
            absoluteDeadline = schedule(absoluteTimeout, absoluteTimeoutReason);
        }
    }

    void recordActivity() {
        synchronized (deadlineMonitor) {
            if (closed.get() || !isEnabled(idleTimeout)) {
                return;
            }
            cancel(idleDeadline);
            idleDeadline = schedule(idleTimeout, TimeoutReason.IDLE);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelDeadlines();
    }

    private ScheduledFuture<?> schedule(Duration timeout, final TimeoutReason reason) {
        if (!isEnabled(timeout)) {
            return null;
        }
        long delayMillis = Math.max(1L, timeout.toMillis());
        return scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                expire(reason);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void expire(TimeoutReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelDeadlines();
        timeoutHandler.accept(reason);
    }

    private void cancelDeadlines() {
        synchronized (deadlineMonitor) {
            cancel(idleDeadline);
            cancel(absoluteDeadline);
            idleDeadline = null;
            absoluteDeadline = null;
        }
    }

    private void cancel(ScheduledFuture<?> deadline) {
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    private boolean isEnabled(Duration timeout) {
        return !timeout.isZero() && !timeout.isNegative();
    }
}
