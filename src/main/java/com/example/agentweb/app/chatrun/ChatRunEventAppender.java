package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.EventSequenceRange;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Coordinates aggregate sequence allocation, event persistence and after-commit publication.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class ChatRunEventAppender {

    private final ChatRunRepository runRepository;
    private final ChatRunEventStore eventStore;
    private final ChatRunEventHub eventHub;
    private final AfterCommitExecutor afterCommitExecutor;

    public ChatRunEventAppender(ChatRunRepository runRepository, ChatRunEventStore eventStore,
                                ChatRunEventHub eventHub, AfterCommitExecutor afterCommitExecutor) {
        this.runRepository = runRepository;
        this.eventStore = eventStore;
        this.eventHub = eventHub;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    public List<ChatRunEvent> appendToNewRun(ChatRun run, List<ChatRunEventDraft> drafts, Instant now) {
        EventSequenceRange range = run.allocateEventSequence(drafts.size(), now);
        runRepository.add(run);
        return persistAndPublish(run, range, drafts, now);
    }

    public List<ChatRunEvent> appendToExistingRun(ChatRun run, List<ChatRunEventDraft> drafts, Instant now) {
        EventSequenceRange range = run.allocateEventSequence(drafts.size(), now);
        runRepository.update(run);
        return persistAndPublish(run, range, drafts, now);
    }

    public void afterCommit(Runnable action) {
        afterCommitExecutor.execute(action);
    }

    private List<ChatRunEvent> persistAndPublish(ChatRun run, EventSequenceRange range,
                                                List<ChatRunEventDraft> drafts, Instant now) {
        final List<ChatRunEvent> events = eventStore.appendAssigned(run.getId(), range, drafts, now);
        afterCommitExecutor.execute(new Runnable() {
            @Override
            public void run() {
                eventHub.publish(events);
            }
        });
        return events;
    }
}
