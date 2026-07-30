package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.RunSummary;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentStateCheckpointPayload;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.AgentUsage;

/**
 * Maps AgentKit terminal state into the provider-neutral ChatRun result contract.
 *
 * @author alex
 * @since 2026-07-29
 */
public final class NativeRunSummaryMapper {

    public AgentExecutionResult map(RunSummary summary) {
        AgentStreamResult streamResult = switch (summary.reason()) {
            case SUCCESS -> AgentStreamResult.completed(0);
            case STOPPED -> AgentStreamResult.completed(-1);
            case TIMEOUT -> AgentStreamResult.terminated(
                    -1, AgentStreamResult.TerminationReason.HARD_TIMEOUT);
            case ERROR, REJECTED -> AgentStreamResult.completed(1);
        };
        AgentStateCheckpointPayload checkpoint = summary.reason()
                == com.anthropic.agentkit.interfaces.engine.ExitReason.SUCCESS
                && !summary.stateSnapshot().trim().isEmpty()
                ? new AgentStateCheckpointPayload(summary.stateSnapshot(), "") : null;
        RunSummary.Usage usage = summary.usage();
        return new AgentExecutionResult(streamResult, checkpoint,
                new AgentUsage(usage.inputTokens(), usage.outputTokens(),
                        usage.cacheReadInputTokens()),
                summary.reason().name(), summary.errorDetail());
    }
}
