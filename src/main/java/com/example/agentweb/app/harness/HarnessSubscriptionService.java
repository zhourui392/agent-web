package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 run 存在并建立 race-free 的 SQLite replay + live 订阅。
 *
 * <p>照搬 chat 域 {@code ChatRunSubscriptionService} 模式：先开 live subscription，
 * 再 replay 历史事件分页 500，replay 完成后 {@code live.activateAfter(highWatermark)}
 * 切实时，启动 heartbeat。</p>
 *
 * @author zhourui(V33215020)
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessSubscriptionService {

    private static final int REPLAY_PAGE_SIZE = 500;

    private final HarnessRunRepository runRepository;
    private final HarnessRunEventStore eventStore;
    private final HarnessRunEventHub eventHub;
    private final TaskScheduler scheduler;
    private final HarnessRunStreamSettings settings;

    public HarnessSubscriptionService(HarnessRunRepository runRepository,
                                      HarnessRunEventStore eventStore,
                                      HarnessRunEventHub eventHub,
                                      TaskScheduler scheduler,
                                      HarnessRunStreamSettings settings) {
        this.runRepository = runRepository;
        this.eventStore = eventStore;
        this.eventHub = eventHub;
        this.scheduler = scheduler;
        this.settings = settings;
    }

    public HarnessRunStreamHandle subscribe(String runIdValue, long cursor, HarnessRunStreamSink sink) {
        if (cursor < 0L) {
            throw new IllegalArgumentException("event cursor must not be negative");
        }
        requireRunExists(runIdValue);
        long earliest = eventStore.findEarliestSequence(runIdValue);
        if (earliest > 0L && cursor < earliest - 1L) {
            long lastSeq = eventStore.findLastSequence(runIdValue);
            throw new HarnessEventCursorExpiredException(runIdValue, earliest, lastSeq);
        }

        final AtomicLong lastSent = new AtomicLong(cursor);
        final AtomicReference<HarnessRunStreamHandle> handleReference =
                new AtomicReference<HarnessRunStreamHandle>();
        HarnessRunEventSubscription live = eventHub.open(runIdValue, new HarnessRunEventConsumer() {
            @Override
            public void accept(HarnessRunEvent event) {
                deliverIfNew(event, lastSent, sink, handleReference);
            }

            @Override
            public void overflow() {
                sink.fail(new SlowHarnessSubscriberException(runIdValue));
                closeReferenced(handleReference);
            }
        });
        HarnessRunStreamHandle handle = new HarnessRunStreamHandle(live);
        handleReference.set(handle);

        try {
            long highWatermark = eventStore.findLastSequence(runIdValue);
            replay(runIdValue, cursor, highWatermark, lastSent, sink, handleReference);
            if (handle.isClosed()) {
                return handle;
            }
            live.activateAfter(highWatermark);
            ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    sink.ping();
                }
            }, Duration.ofSeconds(Math.max(1, settings.getHeartbeatSeconds())));
            handle.setHeartbeat(heartbeat);
            return handle;
        } catch (RuntimeException ex) {
            handle.close();
            throw ex;
        }
    }

    private void replay(String runId, long cursor, long highWatermark, AtomicLong lastSent,
                        HarnessRunStreamSink sink, AtomicReference<HarnessRunStreamHandle> handleReference) {
        long pageCursor = cursor;
        while (pageCursor < highWatermark) {
            List<HarnessRunEvent> page = eventStore.findAfterThrough(
                    runId, pageCursor, highWatermark, REPLAY_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (HarnessRunEvent event : page) {
                deliverIfNew(event, lastSent, sink, handleReference);
                pageCursor = event.getSequence();
            }
            if (page.size() < REPLAY_PAGE_SIZE) {
                break;
            }
        }
    }

    private void deliverIfNew(HarnessRunEvent event, AtomicLong lastSent, HarnessRunStreamSink sink,
                              AtomicReference<HarnessRunStreamHandle> handleReference) {
        while (true) {
            long previous = lastSent.get();
            if (event.getSequence() <= previous) {
                return;
            }
            if (lastSent.compareAndSet(previous, event.getSequence())) {
                sink.send(event);
                return;
            }
        }
    }

    private void requireRunExists(String runId) {
        Optional<?> found = runRepository.findById(runId);
        if (!found.isPresent()) {
            throw new HarnessRunNotFoundException(runId);
        }
    }

    private void closeReferenced(AtomicReference<HarnessRunStreamHandle> reference) {
        HarnessRunStreamHandle handle = reference.get();
        if (handle != null) {
            handle.close();
        }
    }
}