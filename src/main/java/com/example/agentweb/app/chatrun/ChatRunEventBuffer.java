package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import org.springframework.scheduling.TaskScheduler;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * Per-run ordered event buffer. All flushes for one run are serialized by this object.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class ChatRunEventBuffer implements AutoCloseable {

    private final ChatRunId runId;
    private final ChatRunLifecycleService lifecycleService;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Consumer<RuntimeException> failureConsumer;
    private final int flushIntervalMs;
    private final int flushMaxEvents;
    private final int flushMaxBytes;
    private final List<ChatRunEventDraft> pending = new ArrayList<ChatRunEventDraft>();
    private int pendingBytes;
    private ScheduledFuture<?> scheduledFlush;
    private RuntimeException failure;
    private boolean closed;

    public ChatRunEventBuffer(ChatRunId runId,
                              ChatRunLifecycleService lifecycleService,
                              TaskScheduler scheduler,
                              ChatRunStreamSettings settings,
                              Clock clock,
                              Consumer<RuntimeException> failureConsumer) {
        this.runId = runId;
        this.lifecycleService = lifecycleService;
        this.scheduler = scheduler;
        this.clock = clock;
        this.failureConsumer = failureConsumer;
        this.flushIntervalMs = Math.max(1, settings.getFlushIntervalMs());
        this.flushMaxEvents = Math.max(1, settings.getFlushMaxEvents());
        this.flushMaxBytes = Math.max(1, settings.getFlushMaxBytes());
    }

    public synchronized void append(String eventType, String payload) {
        requireWritable();
        ChatRunEventDraft draft = new ChatRunEventDraft(eventType, payload);
        pending.add(draft);
        pendingBytes += payload.getBytes(StandardCharsets.UTF_8).length;
        scheduleIfNeeded();
        if (pending.size() >= flushMaxEvents || pendingBytes >= flushMaxBytes) {
            flushPending();
        }
    }

    public synchronized void flush() {
        requireHealthy();
        flushPending();
    }

    public synchronized RuntimeException getFailure() {
        return failure;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            if (failure == null) {
                flushPending();
            }
        } finally {
            closed = true;
            cancelScheduledFlush();
        }
    }

    private void scheduleIfNeeded() {
        if (scheduledFlush != null) {
            return;
        }
        Instant flushAt = clock.instant().plusMillis(flushIntervalMs);
        scheduledFlush = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    flush();
                } catch (RuntimeException ignored) {
                    // The failure callback owns process cancellation and run finalization.
                }
            }
        }, flushAt);
    }

    private void flushPending() {
        if (pending.isEmpty()) {
            cancelScheduledFlush();
            return;
        }
        List<ChatRunEventDraft> batch = new ArrayList<ChatRunEventDraft>(pending);
        pending.clear();
        pendingBytes = 0;
        cancelScheduledFlush();
        try {
            lifecycleService.appendBatch(runId, batch);
        } catch (RuntimeException ex) {
            markFailed(ex);
            throw ex;
        }
    }

    private void cancelScheduledFlush() {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }
    }

    private void markFailed(RuntimeException ex) {
        if (failure != null) {
            return;
        }
        failure = ex;
        failureConsumer.accept(ex);
    }

    private void requireWritable() {
        requireHealthy();
        if (closed) {
            throw new IllegalStateException("chat run event buffer is closed");
        }
    }

    private void requireHealthy() {
        if (failure != null) {
            throw failure;
        }
    }
}
