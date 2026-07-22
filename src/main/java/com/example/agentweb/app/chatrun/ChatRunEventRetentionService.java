package com.example.agentweb.app.chatrun;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Coordinates bounded retention passes without touching active runs or chat messages.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
public class ChatRunEventRetentionService {

    private static final int EVENT_BATCH_SIZE = 5_000;
    private static final int RUN_BATCH_SIZE = 500;

    private final ChatRunEventStore eventStore;
    private final ChatRunRetentionStore retentionStore;
    private final ChatRunStreamSettings settings;
    private final Clock clock;

    public ChatRunEventRetentionService(ChatRunEventStore eventStore,
                                        ChatRunRetentionStore retentionStore,
                                        ChatRunStreamSettings settings,
                                        Clock clock) {
        this.eventStore = eventStore;
        this.retentionStore = retentionStore;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public ChatRunRetentionResult purgeExpired() {
        Instant now = clock.instant();
        Instant eventCutoff = now.minus(Duration.ofHours(
                Math.max(1, settings.getEventRetentionHours())));
        Instant runCutoff = now.minus(Duration.ofDays(
                Math.max(1, settings.getRunRetentionDays())));
        int events = eventStore.deleteBefore(eventCutoff, EVENT_BATCH_SIZE);
        int runs = retentionStore.deleteTerminalRunsBefore(runCutoff, RUN_BATCH_SIZE);
        return new ChatRunRetentionResult(events, runs);
    }
}
