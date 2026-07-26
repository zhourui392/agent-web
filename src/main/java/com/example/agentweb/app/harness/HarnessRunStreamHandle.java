package com.example.agentweb.app.harness;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 replay + live 订阅的幂等生命周期句柄。
 *
 * @author zhourui(V33215020)
 */
public final class HarnessRunStreamHandle {

    private final HarnessRunEventSubscription subscription;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> heartbeat;

    HarnessRunStreamHandle(HarnessRunEventSubscription subscription) {
        this.subscription = subscription;
    }

    void setHeartbeat(ScheduledFuture<?> heartbeat) {
        this.heartbeat = heartbeat;
        if (closed.get() && heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscription.close();
        ScheduledFuture<?> scheduled = heartbeat;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}