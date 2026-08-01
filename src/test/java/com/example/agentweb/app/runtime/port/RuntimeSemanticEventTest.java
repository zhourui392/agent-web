package com.example.agentweb.app.runtime.port;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("在仓库 service-a 执行 TEST 类命令",
                event.getSemanticEvents().get(0).getData()
                        .get("commandSummary"));
        assertEquals("RUNNING", event.getSemanticEvents().get(0)
                .getData().get("status"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.getSemanticEvents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> event.getSemanticEvents().get(0).getData()
                        .put("unsafe", "/home/alex/secret"));
    }

    @Test
    void commandCompletionShouldExposeOnlyBoundedFactBasedSummaries() {
        RuntimeSemanticEvent succeeded = RuntimeSemanticEvent.commandFinished(
                "service-a", "TEST", Integer.valueOf(0), "SUCCEEDED");
        RuntimeSemanticEvent failed = RuntimeSemanticEvent.commandFinished(
                "service-a", "BUILD", Integer.valueOf(7), "FAILED");

        assertEquals("在仓库 service-a 执行 TEST 类命令",
                succeeded.getData().get("commandSummary"));
        assertEquals("TEST 类命令执行成功（退出码 0）",
                succeeded.getData().get("outputSummary"));
        assertEquals("BUILD 类命令执行失败（退出码 7）",
                failed.getData().get("outputSummary"));
        assertEquals("SUCCEEDED", succeeded.getData().get("status"));
        assertFalse(succeeded.getData().containsKey("command"));
        assertFalse(succeeded.getData().containsKey("output"));
        assertFalse(succeeded.getData().containsKey("stdout"));
        assertFalse(succeeded.getData().containsKey("stderr"));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticEvent.commandFinished(
                        "service-a", "TEST", Integer.valueOf(0), "UNKNOWN"));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticEvent.commandStarted(
                        "service-a\nforged", "TEST"));
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

    @Test
    void toolFinishedShouldAcceptOnlyBoundedNonNegativeObservedDuration() {
        RuntimeSemanticEvent finished = RuntimeSemanticEvent.toolFinished(
                        "repository/read", "call-1", "SUCCEEDED")
                .withDurationMs(17L);

        assertEquals(17L, finished.getData().get("durationMs"));
        assertFalse(finished.getData().containsKey("command"));
        assertFalse(finished.getData().containsKey("output"));
        assertFalse(finished.getData().containsKey("path"));
        assertThrows(UnsupportedOperationException.class,
                () -> finished.getData().put("durationMs", 18L));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticEvent.toolFinished(
                                "repository/read", "call-1", "SUCCEEDED")
                        .withDurationMs(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticEvent.toolFinished(
                                "repository/read", "call-1", "SUCCEEDED")
                        .withDurationMs(
                                RuntimeSemanticEvent
                                        .MAX_TOOL_DURATION_MILLIS + 1L));
        assertThrows(IllegalStateException.class,
                () -> RuntimeSemanticEvent.toolStarted(
                                "repository/read", "call-1", "RUNNING")
                        .withDurationMs(1L));
        assertTrue(finished.toString().contains("durationMs"));
    }
}
