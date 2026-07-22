package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunEventConsumer;
import com.example.agentweb.app.chatrun.ChatRunEventSubscription;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.infra.ResumableChatStreamProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class InMemoryChatRunEventHubTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void inactive_subscriber_should_buffer_then_drop_events_at_replay_watermark() throws Exception {
        InMemoryChatRunEventHub hub = hub(10, 1024);
        ChatRunId runId = ChatRunId.of("run-1");
        List<Long> delivered = new CopyOnWriteArrayList<Long>();
        CountDownLatch deliveredSecond = new CountDownLatch(1);
        ChatRunEventSubscription subscription = hub.open(runId, consumer(delivered, deliveredSecond));

        hub.publish(Collections.singletonList(event(runId, 1L, "one")));
        hub.publish(Collections.singletonList(event(runId, 2L, "two")));
        assertEquals(0, delivered.size());

        subscription.activateAfter(1L);

        assertTrue(deliveredSecond.await(2, TimeUnit.SECONDS));
        assertEquals(Collections.singletonList(Long.valueOf(2L)), delivered);
        assertEquals(1, hub.subscriberCount(runId));
        assertEquals(1, hub.totalSubscriberCount());
        subscription.close();
        assertEquals(0, hub.subscriberCount(runId));
        assertEquals(0, hub.totalSubscriberCount());
        assertEquals(0L, hub.slowConsumerClosedTotal());
    }

    @Test
    void publish_should_not_wait_for_slow_consumer_and_overflow_should_close_only_that_subscriber()
            throws Exception {
        InMemoryChatRunEventHub hub = hub(1, 1024);
        ChatRunId runId = ChatRunId.of("run-1");
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch overflow = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        ChatRunEventSubscription subscription = hub.open(runId, new ChatRunEventConsumer() {
            @Override
            public void accept(ChatRunEvent event) {
                delivered.incrementAndGet();
                try {
                    block.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void overflow() {
                overflow.countDown();
            }
        });
        subscription.activateAfter(0L);

        long started = System.nanoTime();
        hub.publish(Collections.singletonList(event(runId, 1L, "one")));
        hub.publish(Collections.singletonList(event(runId, 2L, "two")));
        hub.publish(Collections.singletonList(event(runId, 3L, "three")));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 200L, "publisher must not block behind subscriber IO");
        assertTrue(overflow.await(2, TimeUnit.SECONDS));
        assertEquals(0, hub.subscriberCount(runId));
        assertEquals(0, hub.totalSubscriberCount());
        assertEquals(1L, hub.slowConsumerClosedTotal());
        subscription.close();
        assertEquals(1L, hub.slowConsumerClosedTotal());
        block.countDown();
    }

    @Test
    void total_subscriber_count_should_span_runs_and_follow_explicit_close() {
        InMemoryChatRunEventHub hub = hub(10, 1024);
        ChatRunId firstRun = ChatRunId.of("run-1");
        ChatRunId secondRun = ChatRunId.of("run-2");
        ChatRunEventSubscription first = hub.open(firstRun,
                consumer(new CopyOnWriteArrayList<Long>(), new CountDownLatch(0)));
        ChatRunEventSubscription second = hub.open(secondRun,
                consumer(new CopyOnWriteArrayList<Long>(), new CountDownLatch(0)));

        assertEquals(1, hub.subscriberCount(firstRun));
        assertEquals(1, hub.subscriberCount(secondRun));
        assertEquals(2, hub.totalSubscriberCount());

        first.close();
        assertEquals(1, hub.totalSubscriberCount());
        second.close();
        assertEquals(0, hub.totalSubscriberCount());
    }

    private InMemoryChatRunEventHub hub(int maxEvents, int maxBytes) {
        ResumableChatStreamProperties properties = new ResumableChatStreamProperties();
        properties.setSubscriberMaxEvents(maxEvents);
        properties.setSubscriberMaxBytes(maxBytes);
        return new InMemoryChatRunEventHub(properties, executor);
    }

    private ChatRunEventConsumer consumer(final List<Long> delivered, final CountDownLatch latch) {
        return new ChatRunEventConsumer() {
            @Override
            public void accept(ChatRunEvent event) {
                delivered.add(event.getSeq());
                latch.countDown();
            }

            @Override
            public void overflow() {
            }
        };
    }

    private ChatRunEvent event(ChatRunId runId, long seq, String payload) {
        return new ChatRunEvent(runId, seq, "chunk", payload, payload.length(), Instant.now());
    }
}
