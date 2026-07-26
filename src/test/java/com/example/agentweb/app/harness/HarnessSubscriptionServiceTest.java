package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.StageContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HarnessSubscriptionService} 单测：验证 replay-then-live race-free 订阅、cursor 过期、run 不存在。
 *
 * @author zhourui(V33215020)
 */
@ExtendWith(MockitoExtension.class)
class HarnessSubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Mock
    private HarnessRunRepository runRepository;

    @Mock
    private HarnessRunEventStore eventStore;

    @Mock
    private HarnessRunEventHub eventHub;

    @Mock
    private TaskScheduler scheduler;

    @Mock
    private HarnessRunStreamSettings settings;

    private HarnessSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new HarnessSubscriptionService(runRepository, eventStore, eventHub,
                scheduler, settings);
    }

    @Test
    void subscribe_should_open_live_then_replay_then_activate() {
        when(runRepository.findById("run-1")).thenReturn(Optional.of(newRun()));
        when(eventStore.findEarliestSequence("run-1")).thenReturn(1L);
        when(eventStore.findLastSequence("run-1")).thenReturn(3L);
        when(settings.getHeartbeatSeconds()).thenReturn(15);
        HarnessRunEventSubscription subscription = mock(HarnessRunEventSubscription.class);
        when(eventHub.open(eq("run-1"), any(HarnessRunEventConsumer.class))).thenReturn(subscription);
        HarnessRunEvent second = event(2L, "STAGE_STARTED");
        HarnessRunEvent third = event(3L, "GATE_PASSED");
        when(eventStore.findAfterThrough("run-1", 1L, 3L, 500))
                .thenReturn(Arrays.asList(second, third));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        HarnessRunStreamSink sink = mock(HarnessRunStreamSink.class);

        service.subscribe("run-1", 1L, sink);

        InOrder order = inOrder(eventStore, eventHub, sink, subscription, scheduler);
        order.verify(eventStore).findEarliestSequence("run-1");
        order.verify(eventHub).open(eq("run-1"), any(HarnessRunEventConsumer.class));
        order.verify(eventStore).findLastSequence("run-1");
        order.verify(eventStore).findAfterThrough("run-1", 1L, 3L, 500);
        order.verify(sink).send(second);
        order.verify(sink).send(third);
        order.verify(subscription).activateAfter(3L);
        order.verify(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    @Test
    void subscribe_with_expired_cursor_should_throw_without_opening_hub() {
        when(runRepository.findById("run-1")).thenReturn(Optional.of(newRun()));
        when(eventStore.findEarliestSequence("run-1")).thenReturn(500L);
        when(eventStore.findLastSequence("run-1")).thenReturn(900L);

        HarnessEventCursorExpiredException error = assertThrows(
                HarnessEventCursorExpiredException.class,
                () -> service.subscribe("run-1", 128L, mock(HarnessRunStreamSink.class)));

        assertEquals(500L, error.getEarliestRetainedSeq());
        assertEquals(900L, error.getLastEventSeq());
        verify(eventHub, never()).open(any(), any());
    }

    @Test
    void subscribe_should_throw_when_run_not_found() {
        when(runRepository.findById("run-1")).thenReturn(Optional.empty());

        assertThrows(HarnessRunNotFoundException.class,
                () -> service.subscribe("run-1", 0L, mock(HarnessRunStreamSink.class)));

        verify(eventHub, never()).open(any(), any());
    }

    @Test
    void subscribe_should_skip_replay_when_high_watermark_equals_cursor() {
        when(runRepository.findById("run-1")).thenReturn(Optional.of(newRun()));
        when(eventStore.findEarliestSequence("run-1")).thenReturn(0L);
        when(eventStore.findLastSequence("run-1")).thenReturn(0L);
        when(settings.getHeartbeatSeconds()).thenReturn(15);
        HarnessRunEventSubscription subscription = mock(HarnessRunEventSubscription.class);
        when(eventHub.open(eq("run-1"), any(HarnessRunEventConsumer.class))).thenReturn(subscription);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        HarnessRunStreamSink sink = mock(HarnessRunStreamSink.class);

        service.subscribe("run-1", 0L, sink);

        verify(eventStore, never()).findAfterThrough(any(), any(Long.class), any(Long.class), any(Integer.class));
        verify(subscription).activateAfter(0L);
    }

    private HarnessRun newRun() {
        return HarnessRun.create(
                "run-1", "Build M1", "/workspace/agent-web", "CODEX", "local",
                "harness@1.0.0", "admin", "create-1",
                StageContract.mvpDefaults(), NOW);
    }

    private HarnessRunEvent event(long sequence, String type) {
        return new HarnessRunEvent("run-1", sequence, type, "ANALYSIS", "admin", null, NOW);
    }
}