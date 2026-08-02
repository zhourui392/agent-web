package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex JSONL 到公共 Runtime Event 的归一化、脱敏与截断契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeEventDecoderTest {

    private static final String SECRET = "decoder-secret-never-visible";

    @Test
    void decodesCodexJsonlTypeAndBoundsSafePayloadBeforeBuildingEvent() {
        RuntimeEventDecoder decoder = new RuntimeEventDecoder(new RuntimeOutputRedactor());

        RuntimeEventDecoder.DecodedEvent decoded = decoder.decode("exec-jsonl", 2L,
                "{\"type\":\"item.completed\",\"credential\":\"" + SECRET + "\"}");

        assertEquals("item.completed", decoded.getProviderEventType());
        assertEquals(RuntimeEventType.OUTPUT, decoded.getEvent().getType());
        assertEquals(2L, decoded.getEvent().getSequence());
        assertFalse(decoded.getEvent().getSafePayload().contains(SECRET));
        assertFalse(decoded.getEvent().getSafePayload().contains("credential"));
        assertEquals("codex event received: item.completed",
                decoded.getEvent().getSafePayload());
        assertFalse(decoded.isTurnFailed());
        assertFalse(decoded.getEvent().assistantText().isPresent());
    }

    @Test
    void normalizesOnlyCompletedCodexAgentMessageAsAssistantText() {
        RuntimeEventDecoder decoder = new RuntimeEventDecoder(
                new RuntimeOutputRedactor());

        RuntimeEventDecoder.DecodedEvent assistant = decoder.decode(
                "exec-agent", 4L,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"type\":\"agent_message\","
                        + "\"text\":\"answer\"}}");
        RuntimeEventDecoder.DecodedEvent technical = decoder.decode(
                "exec-tool", 5L,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"type\":\"command_execution\","
                        + "\"aggregated_output\":\"do not render\"}}");

        assertEquals("answer",
                assistant.getEvent().assistantText().get());
        assertFalse(technical.getEvent().assistantText().isPresent());
        assertEquals("agent_chunk", assistant.getEvent()
                .getSemanticEvents().get(0).getEventType());
        assertTrue(technical.getEvent().getSemanticEvents().isEmpty());
    }

    @Test
    void recognizesTurnFailureAndBoundsMalformedProviderOutput() {
        RuntimeEventDecoder decoder = new RuntimeEventDecoder(new RuntimeOutputRedactor());

        RuntimeEventDecoder.DecodedEvent failed = decoder.decode("exec-failed", 1L,
                "{ \"type\" : \"turn.failed\", \"secret\" : \"" + SECRET + "\" }");
        String oversized = String.join("", java.util.Collections.nCopies(
                RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH + 100, "x"));
        RuntimeEventDecoder.DecodedEvent malformed = decoder.decode("exec-malformed", 3L,
                oversized);

        assertTrue(failed.isTurnFailed());
        assertEquals(RuntimeEventType.DIAGNOSTIC, failed.getEvent().getType());
        assertEquals("turn.failed", failed.getProviderEventType());
        assertEquals(RuntimeEventType.DIAGNOSTIC, malformed.getEvent().getType());
        assertEquals("", malformed.getProviderEventType());
        assertEquals("unstructured provider output suppressed",
                malformed.getEvent().getSafePayload());
    }

    @Test
    void mapsCommandToolAndTestLifecycleWithoutExposingCommandOrOutput() {
        RuntimeEventDecoder decoder = new RuntimeEventDecoder(
                new RuntimeOutputRedactor());
        WorkspaceLayout layout = workspaceLayout();

        RuntimeEventDecoder.DecodedEvent started = decoder.decode(
                "exec-command", 10L,
                "{\"type\":\"item.started\",\"item\":{"
                        + "\"id\":\"item-10\",\"type\":\"command_execution\","
                        + "\"command\":\"./mvnw -q test\","
                        + "\"status\":\"in_progress\"}}",
                null, layout);
        RuntimeEventDecoder.DecodedEvent completed = decoder.decode(
                "exec-command", 11L,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"id\":\"item-10\",\"type\":\"command_execution\","
                        + "\"command\":\"./mvnw -q test\","
                        + "\"aggregated_output\":\"/home/alex/secret " + SECRET + "\","
                        + "\"exit_code\":0,\"status\":\"completed\"}}",
                null, layout);

        assertEquals(Arrays.asList("tool_started", "command_started", "test_progress"),
                eventTypes(started));
        assertEquals(Arrays.asList("tool_finished", "command_finished", "test_progress"),
                eventTypes(completed));
        assertEquals("service-a", started.getEvent().getSemanticEvents()
                .get(1).getData().get("repositoryKey"));
        assertEquals("TEST", started.getEvent().getSemanticEvents()
                .get(1).getData().get("commandClass"));
        assertEquals("在仓库 service-a 执行 TEST 类命令",
                started.getEvent().getSemanticEvents()
                        .get(1).getData().get("commandSummary"));
        assertEquals("RUNNING", started.getEvent().getSemanticEvents()
                .get(1).getData().get("status"));
        assertEquals("TEST 类命令执行成功（退出码 0）",
                completed.getEvent().getSemanticEvents()
                        .get(1).getData().get("outputSummary"));
        assertEquals("SUCCEEDED", completed.getEvent().getSemanticEvents()
                .get(1).getData().get("status"));
        assertFalse(completed.getEvent().getSafePayload().contains("/home/"));
        assertFalse(completed.getEvent().getSafePayload().contains(SECRET));
        assertFalse(completed.getEvent().getSemanticEvents().toString()
                .contains("./mvnw"));
        assertFalse(completed.getEvent().getSemanticEvents().toString()
                .contains("/home/alex/secret"));
        assertFalse(completed.getEvent().getSemanticEvents().toString()
                .contains(SECRET));
    }

    @Test
    void mapsMcpAndFileChangesToExactRepositoryRelativeContracts() {
        RuntimeEventDecoder decoder = new RuntimeEventDecoder(
                new RuntimeOutputRedactor());
        WorkspaceLayout layout = workspaceLayout();
        RuntimeEventDecoder.DecodedEvent mcp = decoder.decode(
                "exec-mcp", 20L,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"id\":\"call-1\",\"type\":\"mcp_tool_call\","
                        + "\"server\":\"repository-query\",\"tool\":\"read_file\","
                        + "\"status\":\"completed\","
                        + "\"result\":{\"content\":[{\"text\":\"/home/private\"}]}}}",
                null, layout);
        RuntimeEventDecoder.DecodedEvent files = decoder.decode(
                "exec-files", 21L,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"id\":\"patch-1\",\"type\":\"file_change\","
                        + "\"status\":\"completed\",\"changes\":["
                        + "{\"path\":\"/workspace/platform/service-b/src/B.java\",\"kind\":\"add\"},"
                        + "{\"path\":\"src/A.java\",\"kind\":\"update\"}]}}",
                null, layout);

        assertEquals(Collections.singletonList("tool_finished"), eventTypes(mcp));
        assertFalse(mcp.getEvent().getSafePayload().contains("/home/private"));
        assertEquals(Arrays.asList("file_changed", "file_changed"), eventTypes(files));
        RuntimeSemanticEvent secondary = files.getEvent().getSemanticEvents().get(0);
        assertEquals("platform/service-b", secondary.getData().get("repositoryKey"));
        assertEquals("src/B.java", secondary.getData().get("path"));
        assertEquals("ADDED", secondary.getData().get("changeType"));
        RuntimeSemanticEvent primary = files.getEvent().getSemanticEvents().get(1);
        assertEquals("service-a", primary.getData().get("repositoryKey"));
        assertEquals("src/A.java", primary.getData().get("path"));
    }

    @Test
    void rejectsOutOfScopeFilesAndSignalsBlockedHighImpactIntentWithoutRawCommand() {
        RuntimeEventDecoder decoder = new RuntimeEventDecoder(
                new RuntimeOutputRedactor());
        WorkspaceLayout layout = workspaceLayout();
        RuntimeEventDecoder.DecodedEvent escaped = decoder.decode(
                "exec-files", 30L,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"id\":\"patch-2\",\"type\":\"file_change\","
                        + "\"status\":\"completed\",\"changes\":["
                        + "{\"path\":\"/workspace/unselected/secret.txt\","
                        + "\"kind\":\"update\"}]}}",
                null, layout);
        RuntimeEventDecoder.DecodedEvent blocked = decoder.decode(
                "exec-command", 31L,
                "{\"type\":\"item.started\",\"item\":{"
                        + "\"id\":\"item-31\",\"type\":\"command_execution\","
                        + "\"command\":\"git push origin master\","
                        + "\"status\":\"in_progress\"}}",
                null, layout);

        assertTrue(escaped.getEvent().getSemanticEvents().isEmpty());
        assertEquals(Collections.singletonList("operation_blocked"), eventTypes(blocked));
        assertTrue(blocked.isOperationBlocked());
        assertEquals("GIT_PUSH", blocked.getEvent().getSemanticEvents()
                .get(0).getData().get("operationType"));
        assertFalse(blocked.getEvent().getSafePayload().contains("git push"));
        assertFalse(blocked.getEvent().getSemanticEvents().toString().contains("origin"));
    }

    private List<String> eventTypes(RuntimeEventDecoder.DecodedEvent decoded) {
        java.util.ArrayList<String> types = new java.util.ArrayList<String>();
        for (RuntimeSemanticEvent event : decoded.getEvent().getSemanticEvents()) {
            types.add(event.getEventType());
        }
        return types;
    }

    private WorkspaceLayout workspaceLayout() {
        return new WorkspaceLayout(
                "/workspace", "/workspace/service-a",
                Arrays.asList("/workspace/service-a", "/workspace/platform/service-b"),
                Arrays.asList("/workspace/service-a", "/workspace/platform/service-b"),
                SandboxMode.WORKSPACE_WRITE);
    }
}
