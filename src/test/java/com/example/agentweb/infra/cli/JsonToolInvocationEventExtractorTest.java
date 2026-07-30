package com.example.agentweb.infra.cli;

import com.example.agentweb.app.chatrun.ToolInvocationEvent;
import com.example.agentweb.domain.chatrun.ToolInvocationKind;
import com.example.agentweb.domain.shared.AgentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonToolInvocationEventExtractorTest {

    private final JsonToolInvocationEventExtractor extractor =
            new JsonToolInvocationEventExtractor(new ObjectMapper());

    @Test
    void codexCommand_shouldKeepNativeMeaningAndCompletionMetadata() {
        List<ToolInvocationEvent> started = extractor.extract(AgentType.CODEX,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\","
                        + "\"type\":\"command_execution\",\"command\":\"/bin/bash -lc pwd\"}}" );
        ToolInvocationEvent.Started start = (ToolInvocationEvent.Started) started.get(0);
        assertEquals(ToolInvocationKind.COMMAND_EXECUTION, start.getKind());
        assertNull(start.getToolName());
        assertEquals("command_execution", start.getItemType());
        assertTrue(start.getInitialInputJson().contains("/bin/bash"));

        ToolInvocationEvent.Completed completed = (ToolInvocationEvent.Completed) extractor.extract(
                AgentType.CODEX, "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\","
                        + "\"type\":\"command_execution\",\"aggregated_output\":\"ok\","
                        + "\"exit_code\":0,\"status\":\"completed\"}}").get(0);
        assertFalse(completed.isError());
        assertEquals(0, completed.getExitCode());
        assertEquals("completed", completed.getProviderStatus());
    }

    @Test
    void claudeParallelDeltas_shouldAssociateByBlockIndex() {
        extractor.extract(AgentType.CLAUDE, start(1, "call-1", "Read"));
        extractor.extract(AgentType.CLAUDE, start(2, "call-2", "Skill"));
        ToolInvocationEvent.InputDelta first = (ToolInvocationEvent.InputDelta) extractor.extract(
                AgentType.CLAUDE, delta(1, "{\\\"file_path\\\":\\\"/tmp/a\\\"}")).get(0);
        ToolInvocationEvent.InputDelta second = (ToolInvocationEvent.InputDelta) extractor.extract(
                AgentType.CLAUDE, delta(2, "{\\\"skill\\\":\\\"review\\\"}")).get(0);
        assertEquals("call-1", first.getCallId());
        assertEquals("call-2", second.getCallId());
    }

    @Test
    void nativeDiagnosisStream_shouldUseClaudeCompatibleToolLifecycle() {
        ToolInvocationEvent.Started started = (ToolInvocationEvent.Started) extractor.extract(
                AgentType.NATIVE, start(3, "native-call", "log_query")).get(0);
        ToolInvocationEvent.InputDelta delta = (ToolInvocationEvent.InputDelta) extractor.extract(
                AgentType.NATIVE, nativeDelta(3, "native-call",
                        "{\\\"traceId\\\":\\\"abc\\\"}")).get(0);
        ToolInvocationEvent.Completed completed = (ToolInvocationEvent.Completed) extractor.extract(
                AgentType.NATIVE,
                "{\"type\":\"user\",\"message\":{\"content\":[{"
                        + "\"type\":\"tool_result\",\"tool_use_id\":\"native-call\","
                        + "\"content\":\"timeout found\",\"is_error\":false}]}}").get(0);

        assertEquals("native-call", started.getCallId());
        assertEquals(ToolInvocationKind.TOOL_USE, started.getKind());
        assertEquals("{}", started.getInitialInputJson());
        assertEquals("native-call", delta.getCallId());
        assertEquals("native-call", completed.getCallId());
        assertEquals("timeout found", completed.getOutputText());
        assertFalse(completed.isError());
    }

    @Test
    void nativeInputDelta_shouldPreferExplicitCallIdOverThreadLocalBlockCorrelation() {
        extractor.extract(AgentType.NATIVE, start(3, "stale-call", "log_query"));

        ToolInvocationEvent.InputDelta delta = (ToolInvocationEvent.InputDelta) extractor.extract(
                AgentType.NATIVE, nativeDelta(3, "actual-call", "{}")).get(0);

        assertEquals("actual-call", delta.getCallId());
    }

    private String start(int index, String id, String name) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
                + "\"index\":" + index + ",\"content_block\":{\"type\":\"tool_use\","
                + "\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":{}}}}";
    }

    private String delta(int index, String partial) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"index\":" + index + ",\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"" + partial + "\"}}}";
    }

    private String nativeDelta(int index, String callId, String partial) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"index\":" + index + ",\"delta\":{\"type\":\"input_json_delta\","
                + "\"tool_use_id\":\"" + callId + "\",\"partial_json\":\"" + partial + "\"}}}";
    }
}
