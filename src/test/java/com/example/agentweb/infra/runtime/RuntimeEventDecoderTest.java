package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.infra.cli.CodexEventNormalizer;
import com.example.agentweb.infra.cli.ClaudeCliDialect;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex JSONL 到公共 Runtime Event 的归一化、脱敏与截断契约。
 *
 * <p>事件识别委托给 {@link CodexEventNormalizer}，未识别事件返回 skipped（event=null）。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeEventDecoderTest {

    private static final String SECRET = "decoder-secret-never-visible";

    private final RuntimeEventDecoder decoder = new RuntimeEventDecoder(
            new RuntimeOutputRedactor(),
            com.example.agentweb.domain.runtime.RuntimeCommandPolicy.platformDefault(),
            new CodexEventNormalizer());

    @Test
    void unrecognizedItemCompletedShouldBeSkipped() {
        RuntimeEventDecoder.DecodedEvent decoded = decoder.decode("exec-jsonl", 2L,
                "{\"type\":\"item.completed\",\"credential\":\"" + SECRET + "\"}");

        assertNull(decoded.getEvent());
        assertEquals("", decoded.getProviderEventType());
        assertFalse(decoded.isTurnFailed());
    }

    @Test
    void normalizesOnlyCompletedCodexAgentMessageAsAssistantText() {
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
    void recognizesTurnFailureAndSkipsMalformedProviderOutput() {
        RuntimeEventDecoder.DecodedEvent failed = decoder.decode("exec-failed", 1L,
                "{ \"type\" : \"turn.failed\", \"secret\" : \"" + SECRET + "\" }");
        String oversized = String.join("", Collections.nCopies(
                RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH + 100, "x"));
        RuntimeEventDecoder.DecodedEvent malformed = decoder.decode("exec-malformed", 3L,
                oversized);

        assertTrue(failed.isTurnFailed());
        assertEquals(RuntimeEventType.DIAGNOSTIC, failed.getEvent().getType());
        assertEquals("turn.failed", failed.getProviderEventType());
        assertNull(malformed.getEvent());
    }

    @Test
    void shouldMapRedactedCommandAndBoundedOutputWhenDecodingCommandLifecycle() {
        // Given
        WorkspaceLayout layout = workspaceLayout();
        RuntimeCapabilityMaterialization capabilities = capabilitiesWithSecret();
        String longOutput = "/home/alex/secret " + SECRET + "\n"
                + String.join("", Collections.nCopies(2100, "x"));

        // When
        RuntimeEventDecoder.DecodedEvent started = decoder.decode(
                "exec-command", 10L,
                "{\"type\":\"item.started\",\"item\":{"
                        + "\"id\":\"item-10\",\"type\":\"command_execution\","
                        + "\"command\":\"./mvnw -q test -Dtoken=" + SECRET + "\","
                        + "\"status\":\"in_progress\"}}",
                capabilities, layout);
        RuntimeEventDecoder.DecodedEvent completed = decoder.decode(
                "exec-command", 11L,
                "{\"type\":\"item.completed\",\"item\":{"
                        + "\"id\":\"item-10\",\"type\":\"command_execution\","
                        + "\"command\":\"./mvnw -q test\","
                        + "\"aggregated_output\":" + json(longOutput) + ","
                        + "\"exit_code\":0,\"status\":\"completed\"}}",
                capabilities, layout);

        // Then
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
        assertEquals("./mvnw -q test -Dtoken=[REDACTED]",
                started.getEvent().getSemanticEvents()
                        .get(0).getData().get("commandContent"));
        assertEquals("TEST 类命令执行成功（退出码 0）",
                completed.getEvent().getSemanticEvents()
                        .get(1).getData().get("outputSummary"));
        assertEquals("SUCCEEDED", completed.getEvent().getSemanticEvents()
                .get(1).getData().get("status"));
        String outputContent = (String) completed.getEvent().getSemanticEvents()
                .get(0).getData().get("outputContent");
        assertTrue(outputContent.startsWith(
                "/home/alex/secret [REDACTED]\n"));
        assertTrue(outputContent.endsWith("字符，已截断)"));
        assertEquals(Boolean.TRUE, completed.getEvent().getSemanticEvents()
                .get(0).getData().get("outputTruncated"));
        assertFalse(completed.getEvent().getSafePayload().contains("/home/"));
        assertFalse(completed.getEvent().getSafePayload().contains(SECRET));
        assertFalse(completed.getEvent().getSemanticEvents().toString()
                .contains(SECRET));
        capabilities.close();
    }

    @Test
    void mapsMcpAndFileChangesToExactRepositoryRelativeContracts() {
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
    void shouldProjectClaudeToolLifecycleToChatSemanticEvents() {
        // Given
        ClaudeCliDialect claude = new ClaudeCliDialect();
        String startedLine = "{\"type\":\"stream_event\","
                + "\"event\":{\"type\":\"content_block_start\","
                + "\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                + "\"id\":\"toolu_123\",\"name\":\"Bash\",\"input\":{}}}}";
        String finishedLine = "{\"type\":\"user\",\"message\":{\"content\":[{"
                + "\"type\":\"tool_result\",\"tool_use_id\":\"toolu_123\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}]}}";

        // When
        RuntimeEventDecoder.DecodedEvent started = decoder.decode(
                "exec-claude", 10L, startedLine, null, null, claude);
        RuntimeEventDecoder.DecodedEvent finished = decoder.decode(
                "exec-claude", 11L, finishedLine, null, null, claude);

        // Then
        assertEquals(Collections.singletonList("tool_started"), eventTypes(started));
        assertEquals("Bash", started.getEvent().getSemanticEvents().get(0)
                .getData().get("tool"));
        assertEquals("toolu_123", started.getEvent().getSemanticEvents().get(0)
                .getData().get("callId"));
        assertEquals(Collections.singletonList("tool_finished"), eventTypes(finished));
        assertEquals("done", finished.getEvent().getSemanticEvents().get(0)
                .getData().get("outputContent"));
        assertEquals("SUCCEEDED", finished.getEvent().getSemanticEvents().get(0)
                .getData().get("status"));
    }

    @Test
    void shouldProjectClaudeBashCommandFromCompletedToolSnapshot() {
        // Given
        ClaudeCliDialect claude = new ClaudeCliDialect();
        String startedLine = "{\"type\":\"stream_event\","
                + "\"event\":{\"type\":\"content_block_start\","
                + "\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                + "\"id\":\"toolu_bash\",\"name\":\"Bash\",\"input\":{}}}}";
        String assistantSnapshot = "{\"type\":\"assistant\",\"message\":{"
                + "\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"toolu_bash\",\"name\":\"Bash\","
                + "\"input\":{\"command\":\"pwd && ls -la\","
                + "\"description\":\"inspect workspace\"}}]}}";

        // When
        decoder.decode("exec-claude-bash", 20L, startedLine,
                null, null, claude);
        RuntimeEventDecoder.DecodedEvent snapshot = decoder.decode(
                "exec-claude-bash", 21L, assistantSnapshot,
                null, null, claude);

        // Then
        assertEquals(Collections.singletonList("tool_started"),
                eventTypes(snapshot));
        assertEquals("Bash", snapshot.getEvent().getSemanticEvents().get(0)
                .getData().get("tool"));
        assertEquals("pwd && ls -la", snapshot.getEvent().getSemanticEvents()
                .get(0).getData().get("commandContent"));
    }

    @Test
    void shouldProjectClaudeBashCommandFromInputJsonDelta() {
        // Given
        ClaudeCliDialect claude = new ClaudeCliDialect();
        String startedLine = "{\"type\":\"stream_event\","
                + "\"event\":{\"type\":\"content_block_start\","
                + "\"index\":2,\"content_block\":{\"type\":\"tool_use\","
                + "\"id\":\"toolu_delta\",\"name\":\"Bash\",\"input\":{}}}}";
        String deltaStart = "{\"type\":\"stream_event\","
                + "\"event\":{\"type\":\"content_block_delta\","
                + "\"index\":2,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"{\\\"command\\\":\\\"git \"}}}";
        String deltaEnd = "{\"type\":\"stream_event\","
                + "\"event\":{\"type\":\"content_block_delta\","
                + "\"index\":2,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"status\\\"}\"}}}";

        // When
        decoder.decode("exec-claude-delta", 30L, startedLine,
                null, null, claude);
        RuntimeEventDecoder.DecodedEvent firstDelta = decoder.decode(
                "exec-claude-delta", 31L, deltaStart,
                null, null, claude);
        RuntimeEventDecoder.DecodedEvent delta = decoder.decode(
                "exec-claude-delta", 32L, deltaEnd,
                null, null, claude);

        // Then
        assertEquals(Collections.emptyList(), eventTypes(firstDelta));
        assertEquals(Collections.singletonList("tool_started"),
                eventTypes(delta));
        assertEquals("git status", delta.getEvent().getSemanticEvents().get(0)
                .getData().get("commandContent"));
    }

    @Test
    void rejectsOutOfScopeFilesAndSignalsBlockedHighImpactIntentWithoutRawCommand() {
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

    private RuntimeCapabilityMaterialization capabilitiesWithSecret() {
        java.util.Map<String, char[]> secrets =
                new java.util.LinkedHashMap<String, char[]>();
        secrets.put("RUNTIME_TOKEN", SECRET.toCharArray());
        return new RuntimeCapabilityMaterialization(
                "a".repeat(64),
                Collections.<RuntimeCapabilityMaterialization.MaterializedSkill>emptyList(),
                Collections.<RuntimeCapabilityMaterialization.MaterializedMcpServer>emptyList(),
                secrets);
    }

    private String json(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
