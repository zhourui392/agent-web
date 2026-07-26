package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessRunEvent;
import com.example.agentweb.app.harness.HarnessRunEventConsumer;
import com.example.agentweb.app.harness.HarnessRunEventSubscription;
import com.example.agentweb.app.harness.HarnessRunStreamSettings;
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
 * {@link InMemoryHarnessRunEventHub} 纯单测：缓冲/激活、慢消费者溢出、多 run 计数。
 *
 * @author zhourui(V33215020)
 */
class InMemoryHarnessRunEventHubTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void inactive_subscriber_should_buffer_then_drop_events_at_replay_watermark() throws Exception {
        InMemoryHarnessRunEventHub hub = hub(10, 1024);
        List<Long> delivered = new CopyOnWriteArrayList<Long>();
        CountDownLatch deliveredSecond = new CountDownLatch(1);
        HarnessRunEventSubscription subscription = hub.open("run-1", consumer(delivered, deliveredSecond));

        hub.publish(Collections.singletonList(event(1L, "STAGE_STARTED")));
        hub.publish(Collections.singletonList(event(2L, "GATE_PASSED")));
        assertEquals(0, delivered.size());

        subscription.activateAfter(1L);

        assertTrue(deliveredSecond.await(2, TimeUnit.SECONDS));
        assertEquals(Collections.singletonList(Long.valueOf(2L)), delivered);
        assertEquals(1, hub.subscriberCount("run-1"));
        assertEquals(1, hub.totalSubscriberCount());
        subscription.close();
        assertEquals(0, hub.subscriberCount("run-1"));
        assertEquals(0, hub.totalSubscriberCount());
        assertEquals(0L, hub.slowConsumerClosedTotal());
    }

    @Test
    void publish_should_not_wait_for_slow_consumer_and_overflow_should_close_only_that_subscriber()
            throws Exception {
        InMemoryHarnessRunEventHub hub = hub(1, 1024);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch overflow = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        HarnessRunEventSubscription subscription = hub.open("run-1", new HarnessRunEventConsumer() {
            @Override
            public void accept(HarnessRunEvent event) {
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
        hub.publish(Collections.singletonList(event(1L, "one")));
        hub.publish(Collections.singletonList(event(2L, "two")));
        hub.publish(Collections.singletonList(event(3L, "three")));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 200L, "publisher must not block behind subscriber IO");
        assertTrue(overflow.await(2, TimeUnit.SECONDS));
        assertEquals(0, hub.subscriberCount("run-1"));
        assertEquals(0, hub.totalSubscriberCount());
        assertEquals(1L, hub.slowConsumerClosedTotal());
        subscription.close();
        assertEquals(1L, hub.slowConsumerClosedTotal());
        block.countDown();
    }

    @Test
    void total_subscriber_count_should_span_runs_and_follow_explicit_close() {
        InMemoryHarnessRunEventHub hub = hub(10, 1024);
        HarnessRunEventSubscription first = hub.open("run-1",
                consumer(new CopyOnWriteArrayList<Long>(), new CountDownLatch(0)));
        HarnessRunEventSubscription second = hub.open("run-2",
                consumer(new CopyOnWriteArrayList<Long>(), new CountDownLatch(0)));

        assertEquals(1, hub.subscriberCount("run-1"));
        assertEquals(1, hub.subscriberCount("run-2"));
        assertEquals(2, hub.totalSubscriberCount());

        first.close();
        assertEquals(1, hub.totalSubscriberCount());
        second.close();
        assertEquals(0, hub.totalSubscriberCount());
    }

    private InMemoryHarnessRunEventHub hub(int maxEvents, int maxBytes) {
        HarnessRunStreamSettings settings = new HarnessRunStreamSettings() {
            @Override
            public int getHeartbeatSeconds() { return 15; }
            @Override
            public int getSubscriberMaxEvents() { return maxEvents; }
            @Override
            public int getSubscriberMaxBytes() { return maxBytes; }
        };
        return new InMemoryHarnessRunEventHub(settings, executor);
    }

    private HarnessRunEventConsumer consumer(final List<Long> delivered, final CountDownLatch latch) {
        return new HarnessRunEventConsumer() {
            @Override
            public void accept(HarnessRunEvent event) {
                delivered.add(event.getSequence());
                latch.countDown();
            }

            @Override
            public void overflow() {
            }
        };
    }

    private HarnessRunEvent event(long sequence, String type) {
        return new HarnessRunEvent("run-1", sequence, type, "ANALYSIS", "admin", null,
                Instant.now());
    }
}