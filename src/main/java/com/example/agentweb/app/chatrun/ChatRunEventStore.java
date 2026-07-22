package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.EventSequenceRange;

import java.time.Instant;
import java.util.List;

/**
 * Application port for the append-only run event projection.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunEventStore {

    List<ChatRunEvent> appendAssigned(ChatRunId runId, EventSequenceRange range,
                                      List<ChatRunEventDraft> drafts, Instant createdAt);

    List<ChatRunEvent> findAfterThrough(ChatRunId runId, long afterExclusive,
                                       long throughInclusive, int limit);

    long findEarliestSequence(ChatRunId runId);

    int deleteBefore(Instant cutoff, int limit);
}
