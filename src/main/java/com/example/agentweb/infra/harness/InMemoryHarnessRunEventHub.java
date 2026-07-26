package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessRunEvent;
import com.example.agentweb.app.harness.HarnessRunEventConsumer;
import com.example.agentweb.app.harness.HarnessRunEventHub;
import com.example.agentweb.app.harness.HarnessRunEventSubscription;
import com.example.agentweb.app.harness.HarnessRunStreamSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory live fan-out hub。每个订阅者拥有有界异步队列，
 * CLI 输出线程只 enqueue 不做网络 IO。
 *
 * <p>照搬 chat 域 {@code InMemoryChatRunEventHub} 模式，适配 harness 事件类型。</p>
 *
 * @author zhourui(V33215020)
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class InMemoryHarnessRunEventHub implements HarnessRunEventHub {

    private final Map<String, Set<Subscriber>> subscribers =
            new ConcurrentHashMap<String, Set<Subscriber>>();
    private final AtomicLong slowConsumerClosed = new AtomicLong();
    private final int maxEvents;
    private final int maxBytes;
    private final Executor executor;

    public InMemoryHarnessRunEventHub(HarnessRunStreamSettings properties,
                                      @Qualifier("harnessSseExecutor") Executor executor) {
        this.maxEvents = Math.max(1, properties.getSubscriberMaxEvents());
        this.maxBytes = Math.max(1, properties.getSubscriberMaxBytes());
        this.executor = executor;
    }

    @Override
    public HarnessRunEventSubscription open(String runId, HarnessRunEventConsumer consumer) {
        Subscriber subscriber = new Subscriber(runId, consumer);
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArraySet<Subscriber>())
                .add(subscriber);
        return subscriber;
    }

    @Override
    public void publish(List<HarnessRunEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (HarnessRunEvent event : events) {
            Set<Subscriber> runSubscribers = subscribers.get(event.getRunId());
            if (runSubscribers == null) {
                continue;
            }
            for (Subscriber subscriber : runSubscribers) {
                subscriber.offer(event);
            }
        }
    }

    @Override
    public int subscriberCount(String runId) {
        Set<Subscriber> found = subscribers.get(runId);
        return found == null ? 0 : found.size();
    }

    @Override
    public int totalSubscriberCount() {
        int total = 0;
        for (Set<Subscriber> runSubscribers : subscribers.values()) {
            total += runSubscribers.size();
        }
        return total;
    }

    @Override
    public long slowConsumerClosedTotal() {
        return slowConsumerClosed.get();
    }

    private final class Subscriber implements HarnessRunEventSubscription {

        private final String runId;
        private final HarnessRunEventConsumer consumer;
        private final Queue<HarnessRunEvent> queue = new ArrayDeque<HarnessRunEvent>();
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private boolean active;
        private boolean closed;
        private long lastDeliveredSeq;
        private int queuedBytes;

        private Subscriber(String runId, HarnessRunEventConsumer consumer) {
            this.runId = runId;
            this.consumer = consumer;
        }

        private void offer(HarnessRunEvent event) {
            boolean overflow;
            synchronized (this) {
                if (closed || event.getSequence() <= lastDeliveredSeq) {
                    return;
                }
                overflow = queue.size() >= maxEvents
                        || queuedBytes + event.getPayloadSize() > maxBytes;
                if (!overflow) {
                    queue.offer(event);
                    queuedBytes += event.getPayloadSize();
                }
            }
            if (overflow) {
                closeForOverflow();
                return;
            }
            scheduleDrainIfReady();
        }

        @Override
        public void activateAfter(long highWatermark) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                lastDeliveredSeq = Math.max(lastDeliveredSeq, highWatermark);
                active = true;
            }
            scheduleDrainIfReady();
        }

        @Override
        public void close() {
            closeInternal(false);
        }

        private void closeForOverflow() {
            log.warn("harness-subscriber-closed runId={} reason=SLOW_CONSUMER", runId);
            if (closeInternal(true)) {
                slowConsumerClosed.incrementAndGet();
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        consumer.overflow();
                    }
                });
            }
        }

        private boolean closeInternal(boolean overflow) {
            synchronized (this) {
                if (closed) {
                    return false;
                }
                closed = true;
                queue.clear();
                queuedBytes = 0;
            }
            Set<Subscriber> runSubscribers = subscribers.get(runId);
            if (runSubscribers != null) {
                runSubscribers.remove(this);
                if (runSubscribers.isEmpty()) {
                    subscribers.remove(runId, runSubscribers);
                }
            }
            return true;
        }

        private void scheduleDrainIfReady() {
            synchronized (this) {
                if (closed || !active || queue.isEmpty()) {
                    return;
                }
            }
            if (draining.compareAndSet(false, true)) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        drain();
                    }
                });
            }
        }

        private void drain() {
            try {
                while (true) {
                    HarnessRunEvent next;
                    synchronized (this) {
                        if (closed || !active) {
                            return;
                        }
                        next = queue.poll();
                        if (next == null) {
                            return;
                        }
                        queuedBytes -= next.getPayloadSize();
                        if (next.getSequence() <= lastDeliveredSeq) {
                            continue;
                        }
                        lastDeliveredSeq = next.getSequence();
                    }
                    consumer.accept(next);
                }
            } catch (RuntimeException ex) {
                log.debug("harness-subscriber-send-failed runId={} reason={}",
                        runId, ex.getMessage());
                close();
            } finally {
                draining.set(false);
                scheduleDrainIfReady();
            }
        }
    }
}