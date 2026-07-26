package com.example.agentweb.app.harness;

import com.example.agentweb.app.chatrun.AfterCommitExecutor;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HarnessRunEventPublisher} 单测：验证 pullPendingEvents + afterCommit + eventHub.publish 链路。
 *
 * @author zhourui(V33215020)
 */
@ExtendWith(MockitoExtension.class)
class HarnessRunEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Mock
    private AfterCommitExecutor afterCommitExecutor;

    @Mock
    private HarnessRunEventHub eventHub;

    @InjectMocks
    private HarnessRunEventPublisher publisher;

    @Test
    void publish_should_pull_pending_and_schedule_after_commit() {
        HarnessRun run = newRun();

        publisher.publish(run);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(afterCommitExecutor).execute(captor.capture());
        captor.getValue().run();
        verify(eventHub).publish(anyList());
    }

    @Test
    void publish_should_convert_domain_events_to_run_events() {
        HarnessRun run = newRun();

        publisher.publish(run);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(afterCommitExecutor).execute(captor.capture());
        captor.getValue().run();

        ArgumentCaptor<List<HarnessRunEvent>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventHub).publish(eventsCaptor.capture());
        List<HarnessRunEvent> events = eventsCaptor.getValue();
        assertEquals(1, events.size());
        assertEquals("RUN_CREATED", events.get(0).getEventType());
        assertEquals("run-1", events.get(0).getRunId());
    }

    @Test
    void publish_should_skip_when_no_pending_events() {
        HarnessRun run = newRun();
        run.pullPendingEvents();

        publisher.publish(run);

        verify(afterCommitExecutor, never()).execute(any(Runnable.class));
        verify(eventHub, never()).publish(anyList());
    }

    @Test
    void publish_should_buffer_multiple_events_in_one_batch() {
        HarnessRun run = newRun();
        run.pullPendingEvents();
        run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1));

        publisher.publish(run);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(afterCommitExecutor).execute(captor.capture());
        captor.getValue().run();

        ArgumentCaptor<List<HarnessRunEvent>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventHub).publish(eventsCaptor.capture());
        List<HarnessRunEvent> events = eventsCaptor.getValue();
        assertEquals(1, events.size());
        assertEquals("STAGE_STARTED", events.get(0).getEventType());
    }

    @Test
    void publish_should_clear_pending_after_pull() {
        HarnessRun run = newRun();

        publisher.publish(run);

        assertTrue(run.pullPendingEvents().isEmpty());
    }

    private HarnessRun newRun() {
        return HarnessRun.create(
                "run-1", "Build M1", "/workspace/agent-web", "CODEX", "local",
                "harness@1.0.0", "admin", "create-1",
                StageContract.mvpDefaults(), NOW);
    }
}