package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Creates isolated ordered buffers for concurrently running chats.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class ChatRunEventBufferFactory {

    private final ChatRunLifecycleService lifecycleService;
    private final TaskScheduler scheduler;
    private final ChatRunStreamSettings settings;
    private final Clock clock;

    public ChatRunEventBufferFactory(ChatRunLifecycleService lifecycleService,
                                     TaskScheduler scheduler,
                                     ChatRunStreamSettings settings,
                                     Clock clock) {
        this.lifecycleService = lifecycleService;
        this.scheduler = scheduler;
        this.settings = settings;
        this.clock = clock;
    }

    public ChatRunEventBuffer open(ChatRunId runId, Consumer<RuntimeException> failureConsumer) {
        return new ChatRunEventBuffer(runId, lifecycleService, scheduler, settings, clock,
                failureConsumer);
    }
}
