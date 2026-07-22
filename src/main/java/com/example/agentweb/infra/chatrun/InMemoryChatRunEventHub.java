package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunEventConsumer;
import com.example.agentweb.app.chatrun.ChatRunEventHub;
import com.example.agentweb.app.chatrun.ChatRunEventSubscription;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.infra.ResumableChatStreamProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * In-memory live fan-out hub. Each subscriber owns a bounded asynchronous queue,
 * so CLI output threads only enqueue and never perform network IO.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
@Slf4j
public class InMemoryChatRunEventHub implements ChatRunEventHub {

    private final Map<ChatRunId, Set<Subscriber>> subscribers =
            new ConcurrentHashMap<ChatRunId, Set<Subscriber>>();
    private final AtomicLong slowConsumerClosed = new AtomicLong();
    private final int maxEvents;
    private final int maxBytes;
    private final Executor executor;

    public InMemoryChatRunEventHub(ResumableChatStreamProperties properties,
                                   @Qualifier("chatRunSubscriberExecutor") Executor executor) {
        this.maxEvents = Math.max(1, properties.getSubscriberMaxEvents());
        this.maxBytes = Math.max(1, properties.getSubscriberMaxBytes());
        this.executor = executor;
    }

    @Override
    public ChatRunEventSubscription open(ChatRunId runId, ChatRunEventConsumer consumer) {
        Subscriber subscriber = new Subscriber(runId, consumer);
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArraySet<Subscriber>())
                .add(subscriber);
        return subscriber;
    }

    @Override
    public void publish(List<ChatRunEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (ChatRunEvent event : events) {
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
    public int subscriberCount(ChatRunId runId) {
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

    private final class Subscriber implements ChatRunEventSubscription {

        private final ChatRunId runId;
        private final ChatRunEventConsumer consumer;
        private final Queue<ChatRunEvent> queue = new ArrayDeque<ChatRunEvent>();
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private boolean active;
        private boolean closed;
        private long lastDeliveredSeq;
        private int queuedBytes;

        private Subscriber(ChatRunId runId, ChatRunEventConsumer consumer) {
            this.runId = runId;
            this.consumer = consumer;
        }

        private void offer(ChatRunEvent event) {
            boolean overflow;
            synchronized (this) {
                if (closed || event.getSeq() <= lastDeliveredSeq) {
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
            log.warn("chat-run-subscriber-closed runId={} reason=SLOW_CONSUMER", runId.getValue());
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
                    ChatRunEvent next;
                    synchronized (this) {
                        if (closed || !active) {
                            return;
                        }
                        next = queue.poll();
                        if (next == null) {
                            return;
                        }
                        queuedBytes -= next.getPayloadSize();
                        if (next.getSeq() <= lastDeliveredSeq) {
                            continue;
                        }
                        lastDeliveredSeq = next.getSeq();
                    }
                    consumer.accept(next);
                }
            } catch (RuntimeException ex) {
                log.debug("chat-run-subscriber-send-failed runId={} reason={}",
                        runId.getValue(), ex.getMessage());
                close();
            } finally {
                draining.set(false);
                scheduleDrainIfReady();
            }
        }
    }
}
