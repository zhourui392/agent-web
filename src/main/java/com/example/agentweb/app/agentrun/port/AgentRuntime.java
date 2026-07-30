package com.example.agentweb.app.agentrun.port;

import com.example.agentweb.domain.shared.AgentType;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Infrastructure adapter for one or more agent identities.
 *
 * @author alex
 * @since 2026-07-29
 */
public interface AgentRuntime {

    Set<AgentType> supportedTypes();

    HistoryDeliveryMode historyDeliveryMode();

    void run(AgentRunInvocation invocation, Consumer<String> onChunk,
             Consumer<AgentExecutionResult> onComplete)
            throws IOException, InterruptedException;

    void stop(String runId);

    String extractResumeId(AgentType type, String rawLine);

    List<String> normalizeChunk(AgentType type, String rawLine);
}
