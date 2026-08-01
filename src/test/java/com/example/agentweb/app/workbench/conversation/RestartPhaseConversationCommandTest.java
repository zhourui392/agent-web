package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase Conversation restart 命令边界测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RestartPhaseConversationCommandTest {

    @Test
    void commandShouldNormalizeRequiredIdempotencyKey() {
        RestartPhaseConversationCommand command = new RestartPhaseConversationCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.SOLUTION_DESIGN,
                "  restart-key-1  ", 3L);

        assertEquals("restart-key-1", command.getIdempotencyKey());
        assertEquals(3L, command.getExpectedVersion());
    }

    @Test
    void commandShouldRejectMissingIdentityOrNegativeVersion() {
        assertThrows(IllegalArgumentException.class, () -> new RestartPhaseConversationCommand(
                null, WorkbenchPhase.SOLUTION_DESIGN, "restart-key-1", 3L));
        assertThrows(IllegalArgumentException.class, () -> new RestartPhaseConversationCommand(
                WorkbenchId.of("workbench-1"), null, "restart-key-1", 3L));
        assertThrows(IllegalArgumentException.class, () -> new RestartPhaseConversationCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.SOLUTION_DESIGN, " ", 3L));
        assertThrows(IllegalArgumentException.class, () -> new RestartPhaseConversationCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.SOLUTION_DESIGN,
                "restart-key-1", -1L));
    }
}
