package com.example.agentweb.app.agentrun.port;

import lombok.Getter;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured provider-neutral terminal result.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public final class AgentExecutionResult {

    private final AgentStreamResult streamResult;
    private final AgentStateCheckpointPayload checkpoint;
    private final AgentUsage usage;
    private final String providerExitReason;
    private final String privateErrorDetail;

    public AgentExecutionResult(AgentStreamResult streamResult,
                                AgentStateCheckpointPayload checkpoint,
                                AgentUsage usage,
                                String providerExitReason,
                                String privateErrorDetail) {
        this.streamResult = Objects.requireNonNull(streamResult, "streamResult");
        this.checkpoint = checkpoint;
        this.usage = usage == null ? AgentUsage.zero() : usage;
        this.providerExitReason = providerExitReason == null ? "" : providerExitReason;
        this.privateErrorDetail = privateErrorDetail == null ? "" : privateErrorDetail;
    }

    public static AgentExecutionResult fromStream(AgentStreamResult streamResult) {
        return new AgentExecutionResult(streamResult, null, AgentUsage.zero(), "", "");
    }

    public static AgentExecutionResult stopped() {
        return new AgentExecutionResult(AgentStreamResult.completed(-1), null,
                AgentUsage.zero(), "STOPPED", "");
    }

    public Optional<AgentStateCheckpointPayload> checkpoint() {
        return Optional.ofNullable(checkpoint);
    }
}
