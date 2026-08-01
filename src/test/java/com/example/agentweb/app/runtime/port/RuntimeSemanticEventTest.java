package com.example.agentweb.app.runtime.port;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 公共 Runtime 结构化语义事件的有界安全合同。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeSemanticEventTest {

    @Test
    void runtimeOutputCanCarryImmutableTypedSemanticEvents() {
        RuntimeSemanticEvent semantic = RuntimeSemanticEvent.commandStarted(
                "service-a", "TEST");
        RuntimeEvent event = new RuntimeEvent(
                "exec-1", 2L, RuntimeEventType.OUTPUT,
                "codex event received: item.started", null,
                Collections.singletonList(semantic));

        assertEquals("command_started",
                event.getSemanticEvents().get(0).getEventType());
        assertEquals("service-a", event.getSemanticEvents().get(0)
                .getData().get("repositoryKey"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.getSemanticEvents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> event.getSemanticEvents().get(0).getData()
                        .put("unsafe", "/home/alex/secret"));
    }

    @Test
    void semanticFactoriesRejectAbsoluteOrEscapingPathsAndRawCommands() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticEvent.fileChanged(
                        "service-a", "/home/alex/secret", "MODIFIED", "sha256:v1"));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticEvent.fileChanged(
                        "service-a", "../secret", "MODIFIED", "sha256:v1"));
        RuntimeSemanticEvent blocked = RuntimeSemanticEvent.operationBlocked(
                "GIT_PUSH", "HIGH_IMPACT_OPERATION_REQUIRES_AUTHORIZATION",
                "高影响操作已被安全策略阻止");
        assertFalse(blocked.getData().containsKey("command"));
        assertFalse(blocked.getData().containsKey("stderr"));
    }
}
