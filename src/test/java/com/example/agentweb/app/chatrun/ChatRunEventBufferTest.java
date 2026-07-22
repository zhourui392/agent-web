package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.infra.ResumableChatStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunEventBufferTest {

    private final ChatRunId runId = ChatRunId.of("run-1");
    private ChatRunLifecycleService lifecycleService;
    private TaskScheduler scheduler;
    private ScheduledFuture<?> scheduled;
    private ResumableChatStreamProperties settings;
    private Clock clock;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(ChatRunLifecycleService.class);
        scheduler = mock(TaskScheduler.class);
        scheduled = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(scheduled).when(scheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        settings = new ResumableChatStreamProperties();
        settings.setFlushIntervalMs(100);
        settings.setFlushMaxEvents(2);
        settings.setFlushMaxBytes(65_536);
        clock = Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void append_should_flush_one_ordered_batch_when_event_threshold_is_reached() {
        ChatRunEventBuffer buffer = new ChatRunEventBuffer(
                runId, lifecycleService, scheduler, settings, clock, error -> { });

        buffer.append("chunk", "one");
        verify(lifecycleService, never()).appendBatch(eq(runId), any());
        buffer.append("chunk", "two");

        ArgumentCaptor<List<ChatRunEventDraft>> batch = eventBatchCaptor();
        verify(lifecycleService).appendBatch(eq(runId), batch.capture());
        assertEquals(2, batch.getValue().size());
        assertEquals("one", batch.getValue().get(0).getPayload());
        assertEquals("two", batch.getValue().get(1).getPayload());
        verify(scheduled).cancel(false);
    }

    @Test
    void scheduled_task_should_flush_pending_event() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ChatRunEventBuffer buffer = new ChatRunEventBuffer(
                runId, lifecycleService, scheduler, settings, clock, error -> { });

        buffer.append("chunk", "one");
        verify(scheduler).schedule(task.capture(), eq(clock.instant().plusMillis(100L)));
        task.getValue().run();

        ArgumentCaptor<List<ChatRunEventDraft>> batch = eventBatchCaptor();
        verify(lifecycleService).appendBatch(eq(runId), batch.capture());
        assertEquals(1, batch.getValue().size());
    }

    @Test
    void persistence_failure_should_notify_once_and_reject_later_appends() {
        settings.setFlushMaxEvents(1);
        RuntimeException failure = new IllegalStateException("sqlite busy");
        doThrow(failure).when(lifecycleService).appendBatch(eq(runId), any());
        AtomicReference<RuntimeException> notified = new AtomicReference<RuntimeException>();
        ChatRunEventBuffer buffer = new ChatRunEventBuffer(
                runId, lifecycleService, scheduler, settings, clock, notified::set);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> buffer.append("chunk", "one"));

        assertEquals(failure, thrown);
        assertEquals(failure, notified.get());
        assertThrows(RuntimeException.class, () -> buffer.append("chunk", "two"));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<ChatRunEventDraft>> eventBatchCaptor() {
        return ArgumentCaptor.forClass((Class<List<ChatRunEventDraft>>) (Class<?>) List.class);
    }
}
