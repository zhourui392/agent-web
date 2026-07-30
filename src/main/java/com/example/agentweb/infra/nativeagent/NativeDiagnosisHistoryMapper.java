package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.AssistantTurn;
import com.anthropic.agentkit.interfaces.engine.StreamJsonHistoryParser;
import com.anthropic.agentkit.interfaces.engine.TurnMessage;
import com.anthropic.agentkit.interfaces.engine.UserTurn;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.agentrun.port.AgentHistoryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts provider-neutral persisted messages into AgentKit's typed conversation history.
 *
 * @author alex
 * @since 2026-07-29
 */
public final class NativeDiagnosisHistoryMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StreamJsonHistoryParser parser;
    private final StreamOutputExtractor outputExtractor;

    public NativeDiagnosisHistoryMapper(StreamOutputExtractor outputExtractor) {
        this(new StreamJsonHistoryParser(), outputExtractor);
    }

    NativeDiagnosisHistoryMapper(StreamJsonHistoryParser parser,
                                 StreamOutputExtractor outputExtractor) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.outputExtractor = Objects.requireNonNull(outputExtractor, "outputExtractor");
    }

    public List<TurnMessage> map(List<AgentHistoryMessage> history) {
        List<TurnMessage> result = new ArrayList<TurnMessage>();
        if (history == null) {
            return result;
        }
        for (AgentHistoryMessage message : history) {
            if ("user".equals(message.role())) {
                result.add(new UserTurn(message.content()));
            } else if ("assistant".equals(message.role())) {
                appendAssistant(message.content(), result);
            }
        }
        return List.copyOf(result);
    }

    private void appendAssistant(String content, List<TurnMessage> result) {
        List<TurnMessage> parsed = parser.parse(content.lines());
        if (!parsed.isEmpty()) {
            result.addAll(parsed);
            return;
        }
        String plainText = outputExtractor.extractPlainText(content);
        if (plainText.isEmpty() && containsMalformedJsonLine(content)) {
            plainText = content.trim();
        }
        if (!plainText.isEmpty()) {
            result.add(AssistantTurn.text(plainText));
        }
    }

    private boolean containsMalformedJsonLine(String content) {
        return content.lines().map(String::trim)
                .filter(line -> line.startsWith("{"))
                .anyMatch(line -> {
                    try {
                        JSON.readTree(line);
                        return false;
                    } catch (Exception ex) {
                        return true;
                    }
                });
    }
}
