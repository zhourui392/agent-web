package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;

import java.util.List;

/**
 * Application port for in-process fan-out after event transactions commit.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunEventHub {

    ChatRunEventSubscription open(ChatRunId runId, ChatRunEventConsumer consumer);

    void publish(List<ChatRunEvent> events);

    int subscriberCount(ChatRunId runId);

    /** Live gauge: total open subscribers across every run on this instance. */
    int totalSubscriberCount();

    /** Monotonic counter: subscribers closed because their bounded queue overflowed. */
    long slowConsumerClosedTotal();
}
