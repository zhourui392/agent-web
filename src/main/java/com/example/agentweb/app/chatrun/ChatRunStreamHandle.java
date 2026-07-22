package com.example.agentweb.app.chatrun;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Idempotent lifecycle handle for one replay-plus-live subscription.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class ChatRunStreamHandle {

    private final ChatRunEventSubscription subscription;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> heartbeat;

    ChatRunStreamHandle(ChatRunEventSubscription subscription) {
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
