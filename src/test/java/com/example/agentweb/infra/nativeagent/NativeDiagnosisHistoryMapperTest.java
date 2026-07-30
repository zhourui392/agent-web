package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.AssistantTurn;
import com.anthropic.agentkit.interfaces.engine.ToolResultTurn;
import com.anthropic.agentkit.interfaces.engine.TurnMessage;
import com.anthropic.agentkit.interfaces.engine.UserTurn;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.agentrun.port.AgentHistoryMessage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * @author alex
 * @since 2026-07-29
 */
class NativeDiagnosisHistoryMapperTest {

    private final NativeDiagnosisHistoryMapper mapper =
            new NativeDiagnosisHistoryMapper(new StreamOutputExtractor());

    @Test
    void map_shouldPreserveUserTextAndPairedAssistantToolTurns() {
        String assistantNdjson = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"checking\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"log_query\","
                + "\"input\":{\"q\":\"error\"}}]}}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tool-1\","
                + "\"content\":\"found timeout\"}]}}";

        List<TurnMessage> turns = mapper.map(Arrays.asList(
                new AgentHistoryMessage("user", "why failed?"),
                new AgentHistoryMessage("assistant", assistantNdjson)));

        assertEquals(3, turns.size());
        assertEquals("why failed?", assertInstanceOf(UserTurn.class, turns.get(0)).text());
        AssistantTurn assistant = assertInstanceOf(AssistantTurn.class, turns.get(1));
        assertEquals("checking", assistant.text());
        assertEquals("tool-1", assistant.toolCalls().get(0).id());
        assertEquals("found timeout",
                assertInstanceOf(ToolResultTurn.class, turns.get(2)).content());
    }

    @Test
    void map_shouldFallbackToPlainAssistantTextAndIgnoreUnknownRoles() {
        List<TurnMessage> turns = mapper.map(Arrays.asList(
                new AgentHistoryMessage("assistant", "legacy plain answer"),
                new AgentHistoryMessage("system", "must not leak"),
                new AgentHistoryMessage("assistant", "{malformed-json")));

        assertEquals(2, turns.size());
        assertEquals("legacy plain answer",
                assertInstanceOf(AssistantTurn.class, turns.get(0)).text());
        assertEquals("{malformed-json",
                assertInstanceOf(AssistantTurn.class, turns.get(1)).text());
    }

    @Test
    void map_shouldDropUnpairedToolCalls() {
        String assistantNdjson = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"orphan\",\"name\":\"log_query\","
                + "\"input\":{}}]}}";

        assertEquals(0, mapper.map(List.of(
                new AgentHistoryMessage("assistant", assistantNdjson))).size());
    }
}
