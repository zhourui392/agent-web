package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authorizes and establishes race-free SQLite replay followed by live subscription.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
public class ChatRunSubscriptionService {

    private static final int REPLAY_PAGE_SIZE = 500;

    private final ChatRunRepository runRepository;
    private final SessionRepository sessionRepository;
    private final ChatRunEventStore eventStore;
    private final ChatRunEventHub eventHub;
    private final TaskScheduler scheduler;
    private final ChatRunStreamSettings settings;

    public ChatRunSubscriptionService(ChatRunRepository runRepository,
                                      SessionRepository sessionRepository,
                                      ChatRunEventStore eventStore,
                                      ChatRunEventHub eventHub,
                                      TaskScheduler scheduler,
                                      ChatRunStreamSettings settings) {
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.eventStore = eventStore;
        this.eventHub = eventHub;
        this.scheduler = scheduler;
        this.settings = settings;
    }

    public ChatRunStreamHandle subscribe(String runIdValue, long cursor, final ChatRunStreamSink sink) {
        if (cursor < 0L) {
            throw new IllegalArgumentException("event cursor must not be negative");
        }
        final ChatRunId runId = ChatRunId.of(runIdValue);
        ChatRun authorized = requireAuthorizedRun(runId);
        long earliest = eventStore.findEarliestSequence(runId);
        if (earliest > 0L && cursor < earliest - 1L) {
            throw new EventCursorExpiredException(runIdValue, earliest, authorized.getLastEventSeq());
        }

        final AtomicLong lastSent = new AtomicLong(cursor);
        final AtomicReference<ChatRunStreamHandle> handleReference =
                new AtomicReference<ChatRunStreamHandle>();
        ChatRunEventSubscription live = eventHub.open(runId, new ChatRunEventConsumer() {
            @Override
            public void accept(ChatRunEvent event) {
                deliverIfNew(event, lastSent, sink, handleReference);
            }

            @Override
            public void overflow() {
                sink.fail(new SlowRunSubscriberException(runId.getValue()));
                closeReferenced(handleReference);
            }
        });
        ChatRunStreamHandle handle = new ChatRunStreamHandle(live);
        handleReference.set(handle);

        try {
            long highWatermark = requireAuthorizedRun(runId).getLastEventSeq();
            replay(runId, cursor, highWatermark, lastSent, sink, handleReference);
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

    private void replay(ChatRunId runId, long cursor, long highWatermark, AtomicLong lastSent,
                        ChatRunStreamSink sink, AtomicReference<ChatRunStreamHandle> handleReference) {
        long pageCursor = cursor;
        while (pageCursor < highWatermark) {
            List<ChatRunEvent> page = eventStore.findAfterThrough(
                    runId, pageCursor, highWatermark, REPLAY_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (ChatRunEvent event : page) {
                deliverIfNew(event, lastSent, sink, handleReference);
                pageCursor = event.getSeq();
                if (isTerminal(event)) {
                    return;
                }
            }
            if (page.size() < REPLAY_PAGE_SIZE) {
                break;
            }
        }
    }

    private void deliverIfNew(ChatRunEvent event, AtomicLong lastSent, ChatRunStreamSink sink,
                              AtomicReference<ChatRunStreamHandle> handleReference) {
        while (true) {
            long previous = lastSent.get();
            if (event.getSeq() <= previous) {
                return;
            }
            if (lastSent.compareAndSet(previous, event.getSeq())) {
                sink.send(event);
                if (isTerminal(event)) {
                    sink.complete();
                    closeReferenced(handleReference);
                }
                return;
            }
        }
    }

    private boolean isTerminal(ChatRunEvent event) {
        return "terminal".equals(event.getEventType());
    }

    private ChatRun requireAuthorizedRun(ChatRunId runId) {
        Optional<ChatRun> found = runRepository.findById(runId);
        if (!found.isPresent()) {
            throw new ChatRunNotFoundException(runId.getValue());
        }
        ChatRun run = found.get();
        ChatSession session = sessionRepository.findById(run.getSessionId());
        if (session == null) {
            throw new ChatRunNotFoundException(runId.getValue());
        }
        return run;
    }

    private void closeReferenced(AtomicReference<ChatRunStreamHandle> reference) {
        ChatRunStreamHandle handle = reference.get();
        if (handle != null) {
            handle.close();
        }
    }
}
