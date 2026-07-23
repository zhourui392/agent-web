package com.example.agentweb.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author alex
 * @since 2026-07-23
 */
class ScheduledTaskDomainTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-23T08:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-07-23T09:00:00Z");

    @Test
    void create_should_establish_identity_owner_and_defaults() {
        ScheduledTask task = ScheduledTask.create(
                "daily", CronExpression.parse("0 0 9 * * ?"), "生成日报", "/tmp", "alice", CREATED_AT);

        assertNotNull(task.getId());
        assertEquals("daily", task.getName());
        assertEquals("0 0 9 * * ?", task.getCronExpr());
        assertEquals("alice", task.getUserId());
        assertEquals(CREATED_AT, task.getCreatedAt());
        assertEquals(CREATED_AT, task.getUpdatedAt());
        assertTrue(task.isEnabled());
        assertNull(task.getLastRunAt());
        assertNull(task.getLastSessionId());
    }

    @Test
    void create_should_reject_blank_required_fields() {
        CronExpression cron = CronExpression.parse("0 0 9 * * ?");

        assertThrows(IllegalArgumentException.class,
                () -> ScheduledTask.create(" ", cron, "prompt", "/tmp", null, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduledTask.create("name", cron, " ", "/tmp", null, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduledTask.create("name", cron, "prompt", " ", null, CREATED_AT));
    }

    @Test
    void revise_should_apply_partial_changes_and_refresh_timestamp() {
        ScheduledTask task = task();

        task.revise("renamed", "0 */15 * * * ?", "new prompt", "/workspace", CHANGED_AT);

        assertEquals("renamed", task.getName());
        assertEquals("0 */15 * * * ?", task.getCronExpr());
        assertEquals("new prompt", task.getPrompt());
        assertEquals("/workspace", task.getWorkingDir());
        assertEquals(CHANGED_AT, task.getUpdatedAt());
    }

    @Test
    void revise_should_keep_null_fields_and_reject_invalid_new_value() {
        ScheduledTask task = task();

        task.revise(null, null, null, null, CHANGED_AT);

        assertEquals("daily", task.getName());
        assertEquals("0 0 9 * * ?", task.getCronExpr());
        assertEquals(CHANGED_AT, task.getUpdatedAt());
        assertThrows(IllegalArgumentException.class,
                () -> task.reschedule(CronExpression.parse("bad"), CHANGED_AT));
    }

    @Test
    void toggle_should_change_state_and_timestamp() {
        ScheduledTask task = task();

        task.toggle(CHANGED_AT);

        assertFalse(task.isEnabled());
        assertEquals(CHANGED_AT, task.getUpdatedAt());
    }

    @Test
    void recordRun_should_keep_execution_result_inside_aggregate() {
        ScheduledTask task = task();

        task.recordRun("session-1", CHANGED_AT);

        assertEquals(CHANGED_AT, task.getLastRunAt());
        assertEquals("session-1", task.getLastSessionId());
    }

    private ScheduledTask task() {
        return ScheduledTask.create(
                "daily", CronExpression.parse("0 0 9 * * ?"), "prompt", "/tmp", "alice", CREATED_AT);
    }
}
