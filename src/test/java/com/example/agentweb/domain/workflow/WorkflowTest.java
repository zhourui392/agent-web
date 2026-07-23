package com.example.agentweb.domain.workflow;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author alex
 * @since 2026-07-23
 */
class WorkflowTest {

    @Test
    void requireRunnable_should_accept_enabled_workflow() {
        assertDoesNotThrow(() -> workflow(true).requireRunnable());
    }

    @Test
    void requireRunnable_should_reject_disabled_workflow() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> workflow(false).requireRunnable());

        assertEquals("工作流已停用: wf-1", error.getMessage());
    }

    private Workflow workflow(boolean enabled) {
        Instant now = Instant.parse("2026-07-23T08:00:00Z");
        return new Workflow("wf-1", "Review", "desc", AgentType.CODEX, "/tmp",
                Collections.singletonList(new WorkflowStep("review", "review {{input}}", 60L)),
                enabled, "alice", now, now);
    }
}
