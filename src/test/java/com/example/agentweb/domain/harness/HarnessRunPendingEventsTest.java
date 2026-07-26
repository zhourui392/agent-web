package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link HarnessRun#pullPendingEvents()} 的缓冲行为：
 * create/业务方法产生的事件进入缓冲，restore 恢复的 run 缓冲为空，
 * pull 后清空，返回不可修改列表。
 *
 * @author zhourui(V33215020)
 */
class HarnessRunPendingEventsTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    void create_should_buffer_run_created_event() {
        HarnessRun run = newRun();

        List<HarnessEvent> pending = run.pullPendingEvents();

        assertEquals(1, pending.size());
        assertEquals("RUN_CREATED", pending.get(0).getEventType());
    }

    @Test
    void business_methods_should_buffer_new_events() {
        HarnessRun run = newRun();
        run.pullPendingEvents();

        run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1));

        List<HarnessEvent> pending = run.pullPendingEvents();
        assertEquals(1, pending.size());
        assertEquals("STAGE_STARTED", pending.get(0).getEventType());
        assertEquals(HarnessStage.ANALYSIS, pending.get(0).getStage());
    }

    @Test
    void pullPendingEvents_should_clear_buffer() {
        HarnessRun run = newRun();

        assertFalse(run.pullPendingEvents().isEmpty());
        assertTrue(run.pullPendingEvents().isEmpty());
    }

    @Test
    void restore_should_start_with_empty_pending_events() {
        HarnessRun created = newRun();
        created.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1));
        created.pullPendingEvents();

        HarnessRun restored = HarnessRun.restore(
                created.getId(), created.getTitle(), created.getWorkingDir(),
                created.getAgentType(), created.getEnvironment(), created.getDefinitionVersion(),
                created.getCreatedBy(), created.getIdempotencyKey(), created.getStatus(),
                created.getCreatedAt(), created.getUpdatedAt(), created.getVersion(),
                created.getStages(), created.getArtifacts(), created.getGateResults(),
                created.getApprovals(), created.getQuestions(), created.getEvents());

        assertTrue(restored.pullPendingEvents().isEmpty());
    }

    @Test
    void pullPendingEvents_should_return_unmodifiable_list() {
        HarnessRun run = newRun();

        List<HarnessEvent> pending = run.pullPendingEvents();

        assertThrows(UnsupportedOperationException.class, () -> pending.add(
                new HarnessEvent(99L, "X", null, "admin", null, NOW)));
    }

    private HarnessRun newRun() {
        return HarnessRun.create(
                "run-1", "Build M1", "/workspace/agent-web", "CODEX", "local",
                "harness@1.0.0", "admin", "create-1",
                StageContract.mvpDefaults(), NOW);
    }
}