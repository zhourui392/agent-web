package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliGateway;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Adapts the existing subprocess gateway to the structured runtime port.
 *
 * @author alex
 * @since 2026-07-29
 */
@Component
public class CliAgentRuntime implements AgentRuntime {

    private final AgentCliGateway cli;

    public CliAgentRuntime(AgentCliGateway cli) {
        this.cli = cli;
    }

    @Override
    public Set<AgentType> supportedTypes() {
        return EnumSet.of(AgentType.CODEX, AgentType.CLAUDE);
    }

    @Override
    public HistoryDeliveryMode historyDeliveryMode() {
        return HistoryDeliveryMode.PROMPT_PREFIX;
    }

    @Override
    public void run(AgentRunInvocation invocation, Consumer<String> onChunk,
                    Consumer<AgentExecutionResult> onComplete)
            throws IOException, InterruptedException {
        cli.runStreamWithResult(invocation.getAgentType(), invocation.getWorkingDir(),
                invocation.getPrompt(), invocation.getRunId(), invocation.getResumeId(),
                invocation.getEnv(), invocation.getTimeoutSeconds(), onChunk,
                result -> onComplete.accept(AgentExecutionResult.fromStream(result)),
                invocation.getUserId(), invocation.getExtraEnv());
    }

    @Override
    public void stop(String runId) {
        cli.stopStream(runId);
    }

    @Override
    public String extractResumeId(AgentType type, String rawLine) {
        return cli.extractResumeId(type, rawLine);
    }

    @Override
    public List<String> normalizeChunk(AgentType type, String rawLine) {
        return cli.normalizeChunk(type, rawLine);
    }
}
