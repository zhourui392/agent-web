package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.ExitReason;
import com.anthropic.agentkit.interfaces.engine.RunSummary;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author alex
 * @since 2026-07-29
 */
class NativeRunSummaryMapperTest {

    private final NativeRunSummaryMapper mapper = new NativeRunSummaryMapper();

    @Test
    void success_shouldMapUsageAndOptionalCheckpoint() {
        AgentExecutionResult result = mapper.map(new RunSummary(ExitReason.SUCCESS, "state-v1",
                new RunSummary.Usage(12L, 5L, 3L), ""));

        assertEquals(AgentStreamResult.completed(0), result.getStreamResult());
        assertTrue(result.checkpoint().isPresent());
        assertEquals("state-v1", result.getCheckpoint().stateSnapshot());
        assertEquals(12L, result.getUsage().inputTokens());
        assertEquals(5L, result.getUsage().outputTokens());
        assertEquals(3L, result.getUsage().cacheReadInputTokens());
        assertEquals("SUCCESS", result.getProviderExitReason());
    }

    @Test
    void nonSuccess_shouldNeverExposeIntermediateCheckpointAndShouldPreservePrivateDetail() {
        AgentExecutionResult stopped = mapper.map(new RunSummary(
                ExitReason.STOPPED, "partial", RunSummary.Usage.zero(), ""));
        AgentExecutionResult timeout = mapper.map(new RunSummary(
                ExitReason.TIMEOUT, "partial", RunSummary.Usage.zero(), "provider timeout"));
        AgentExecutionResult rejected = mapper.map(new RunSummary(
                ExitReason.REJECTED, "partial", RunSummary.Usage.zero(), "secret detail"));

        assertEquals(AgentStreamResult.completed(-1), stopped.getStreamResult());
        assertFalse(stopped.checkpoint().isPresent());
        assertEquals(AgentStreamResult.terminated(-1,
                AgentStreamResult.TerminationReason.HARD_TIMEOUT), timeout.getStreamResult());
        assertFalse(timeout.checkpoint().isPresent());
        assertEquals(AgentStreamResult.completed(1), rejected.getStreamResult());
        assertEquals("REJECTED", rejected.getProviderExitReason());
        assertEquals("secret detail", rejected.getPrivateErrorDetail());
        assertFalse(rejected.checkpoint().isPresent());
    }
}
