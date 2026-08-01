package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 已完成调用方授权后的 ChatRun 事件 replay/live 公共核心。
 *
 * <p>本类不解释 Owner、Session 或业务来源；普通 Chat 与 Workbench 必须分别完成
 * 自己的授权，并把已授权聚合传入。Interface 层不得直接依赖本核心。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public final class AuthorizedChatRunEventReplayService {

    private static final int REPLAY_PAGE_SIZE = 500;

    private final ChatRunRepository runRepository;
    private final ChatRunEventStore eventStore;
    private final ChatRunEventHub eventHub;
    private final TaskScheduler scheduler;
    private final ChatRunStreamSettings settings;

    public AuthorizedChatRunEventReplayService(
            ChatRunRepository runRepository,
            ChatRunEventStore eventStore,
            ChatRunEventHub eventHub,
            TaskScheduler scheduler,
            ChatRunStreamSettings settings) {
        this.runRepository = Objects.requireNonNull(
                runRepository, "runRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.eventHub = Objects.requireNonNull(eventHub, "eventHub");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public ChatRunStreamHandle subscribe(
            ChatRun authorizedRun, long cursor,
            final ChatRunStreamSink sink) {
        Objects.requireNonNull(authorizedRun, "authorizedRun");
        Objects.requireNonNull(sink, "sink");
        if (cursor < 0L) {
            throw new IllegalArgumentException(
                    "event cursor must not be negative");
        }
        final ChatRunId runId = authorizedRun.getId();
        long earliest = eventStore.findEarliestSequence(runId);
        if (earliest > 0L && cursor < earliest - 1L) {
            throw new EventCursorExpiredException(
                    runId.getValue(), earliest,
                    authorizedRun.getLastEventSeq());
        }

        final AtomicLong lastSent = new AtomicLong(cursor);
        final AtomicReference<ChatRunStreamHandle> handleReference =
                new AtomicReference<ChatRunStreamHandle>();
        ChatRunEventSubscription live = eventHub.open(
                runId, new ChatRunEventConsumer() {
                    @Override
                    public void accept(ChatRunEvent event) {
                        deliverIfNew(
                                event, lastSent, sink, handleReference);
                    }

                    @Override
                    public void overflow() {
                        sink.fail(new SlowRunSubscriberException(
                                runId.getValue()));
                        closeReferenced(handleReference);
                    }
                });
        ChatRunStreamHandle handle = new ChatRunStreamHandle(live);
        handleReference.set(handle);

        try {
            long highWatermark = refresh(runId).getLastEventSeq();
            replay(runId, cursor, highWatermark, lastSent,
                    sink, handleReference);
            if (handle.isClosed()) {
                return handle;
            }
            live.activateAfter(highWatermark);
            ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                    new Runnable() {
                        @Override
                        public void run() {
                            sink.ping();
                        }
                    },
                    Duration.ofSeconds(Math.max(
                            1, settings.getHeartbeatSeconds())));
            handle.setHeartbeat(heartbeat);
            return handle;
        } catch (RuntimeException failure) {
            handle.close();
            throw failure;
        }
    }

    private ChatRun refresh(ChatRunId runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ChatRunNotFoundException(
                        runId.getValue()));
    }

    private void replay(
            ChatRunId runId, long cursor, long highWatermark,
            AtomicLong lastSent, ChatRunStreamSink sink,
            AtomicReference<ChatRunStreamHandle> handleReference) {
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

    private void deliverIfNew(
            ChatRunEvent event, AtomicLong lastSent,
            ChatRunStreamSink sink,
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

    private void closeReferenced(
            AtomicReference<ChatRunStreamHandle> reference) {
        ChatRunStreamHandle handle = reference.get();
        if (handle != null) {
            handle.close();
        }
    }
}
