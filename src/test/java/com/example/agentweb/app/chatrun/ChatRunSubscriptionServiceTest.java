package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.ResumableChatStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
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
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunSubscriptionServiceTest {

    private final ChatRunId runId = ChatRunId.of("run-1");
    private final Instant now = Instant.parse("2026-07-22T10:00:00Z");
    private ChatRunRepository runRepository;
    private SessionRepository sessionRepository;
    private ChatRunEventStore eventStore;
    private ChatRunEventHub eventHub;
    private TaskScheduler scheduler;
    private ChatRunSubscriptionService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(ChatRunRepository.class);
        sessionRepository = mock(SessionRepository.class);
        eventStore = mock(ChatRunEventStore.class);
        eventHub = mock(ChatRunEventHub.class);
        scheduler = mock(TaskScheduler.class);
        ResumableChatStreamProperties properties = new ResumableChatStreamProperties();
        properties.setHeartbeatSeconds(15);
        service = new ChatRunSubscriptionService(runRepository, sessionRepository,
                eventStore, eventHub, scheduler, properties);
    }

    @Test
    void subscribe_should_register_before_high_watermark_then_replay_and_activate() {
        ChatRun run = runningRunWithSequence(3L);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(sessionRepository.findById("session-1")).thenReturn(session());
        when(eventStore.findEarliestSequence(runId)).thenReturn(1L);
        ChatRunEventSubscription subscription = mock(ChatRunEventSubscription.class);
        when(eventHub.open(eq(runId), any(ChatRunEventConsumer.class))).thenReturn(subscription);
        ChatRunEvent second = event(2L, "chunk", "two");
        ChatRunEvent third = event(3L, "chunk", "three");
        when(eventStore.findAfterThrough(runId, 1L, 3L, 500))
                .thenReturn(Arrays.asList(second, third));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(java.time.Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        ChatRunStreamSink sink = mock(ChatRunStreamSink.class);

        service.subscribe("run-1", 1L, sink);

        org.mockito.InOrder order = inOrder(eventHub, runRepository, eventStore, sink, subscription);
        order.verify(runRepository).findById(runId);
        order.verify(eventStore).findEarliestSequence(runId);
        order.verify(eventHub).open(eq(runId), any(ChatRunEventConsumer.class));
        order.verify(runRepository).findById(runId);
        order.verify(eventStore).findAfterThrough(runId, 1L, 3L, 500);
        order.verify(sink).send(second);
        order.verify(sink).send(third);
        order.verify(subscription).activateAfter(3L);
    }

    @Test
    void subscribe_with_expired_cursor_should_return_snapshot_metadata_without_opening_hub() {
        ChatRun run = runningRunWithSequence(900L);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(sessionRepository.findById("session-1")).thenReturn(session());
        when(eventStore.findEarliestSequence(runId)).thenReturn(500L);

        EventCursorExpiredException error = assertThrows(EventCursorExpiredException.class,
                () -> service.subscribe("run-1", 128L, mock(ChatRunStreamSink.class)));

        assertEquals(500L, error.getEarliestRetainedSeq());
        assertEquals(900L, error.getLastEventSeq());
        verify(eventHub, never()).open(any(), any());
    }

    @Test
    void terminal_in_replay_should_complete_without_activating_live_subscription() {
        ChatRun run = runningRunWithSequence(1L);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(sessionRepository.findById("session-1")).thenReturn(session());
        when(eventStore.findEarliestSequence(runId)).thenReturn(1L);
        ChatRunEventSubscription subscription = mock(ChatRunEventSubscription.class);
        when(eventHub.open(eq(runId), any(ChatRunEventConsumer.class))).thenReturn(subscription);
        ChatRunEvent terminal = event(1L, "terminal", "{\"status\":\"SUCCEEDED\"}");
        when(eventStore.findAfterThrough(runId, 0L, 1L, 500))
                .thenReturn(Collections.singletonList(terminal));
        ChatRunStreamSink sink = mock(ChatRunStreamSink.class);

        service.subscribe("run-1", 0L, sink);

        verify(sink).send(terminal);
        verify(sink).complete();
        verify(subscription).close();
        verify(subscription, never()).activateAfter(any(Long.class));
    }

    private ChatRun runningRunWithSequence(long sequence) {
        ChatRun run = ChatRun.submit(runId, "session-1", 11L, "key", now);
        run.start(now.plusSeconds(1));
        run.allocateEventSequence((int) sequence, now.plusSeconds(2));
        return run;
    }

    private ChatSession session() {
        return new ChatSession("session-1", AgentType.CODEX, "/workspace", now,
                Collections.emptyList());
    }

    private ChatRunEvent event(long sequence, String type, String payload) {
        return new ChatRunEvent(runId, sequence, type, payload, payload.length(), now);
    }
}
