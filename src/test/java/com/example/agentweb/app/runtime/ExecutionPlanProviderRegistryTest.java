package com.example.agentweb.app.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * @author alex
 * @since 2026-08-01
 */
class ExecutionPlanProviderRegistryTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void shouldDispatchEachOriginToItsOnlyProvider() {
        AgentExecutionPlan chatPlan = mock(AgentExecutionPlan.class);
        AgentExecutionPlan workbenchPlan = mock(AgentExecutionPlan.class);
        ExecutionPlanProvider chatProvider = provider(RunOrigin.CHAT, chatPlan);
        ExecutionPlanProvider workbenchProvider = provider(RunOrigin.WORKBENCH, workbenchPlan);
        ExecutionPlanProviderRegistry registry = new ExecutionPlanProviderRegistry(
                Arrays.asList(workbenchProvider, chatProvider));

        assertSame(chatPlan, registry.prepare(chatRun()));
        assertSame(workbenchPlan, registry.prepare(workbenchRun()));
    }

    @Test
    void shouldRejectMissingOriginProviderAtConstruction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExecutionPlanProviderRegistry(Collections.singletonList(
                        provider(RunOrigin.CHAT, mock(AgentExecutionPlan.class)))));

        assertEquals("execution plan origin WORKBENCH must have exactly one provider, but found 0",
                error.getMessage());
    }

    @Test
    void shouldRejectDuplicateOriginProvidersAtConstruction() {
        ExecutionPlanProvider first = provider(
                RunOrigin.CHAT, mock(AgentExecutionPlan.class));
        ExecutionPlanProvider second = provider(
                RunOrigin.CHAT, mock(AgentExecutionPlan.class));
        ExecutionPlanProvider workbench = provider(
                RunOrigin.WORKBENCH, mock(AgentExecutionPlan.class));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExecutionPlanProviderRegistry(
                        Arrays.asList(first, second, workbench)));

        assertEquals("execution plan origin CHAT must have exactly one provider, but found 2",
                error.getMessage());
    }

    @Test
    void shouldRejectOneProviderClaimingMultipleOrigins() {
        ExecutionPlanProvider catchAll = new ExecutionPlanProvider() {
            @Override
            public boolean supports(RunOrigin origin) {
                return true;
            }

            @Override
            public AgentExecutionPlan prepare(ChatRun run) {
                return mock(AgentExecutionPlan.class);
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExecutionPlanProviderRegistry(
                        Collections.singletonList(catchAll)));

        assertEquals("each execution plan provider must support exactly one origin, but found 2",
                error.getMessage());
    }

    @Test
    void shouldRejectNullProvider() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExecutionPlanProviderRegistry(Arrays.asList(
                        provider(RunOrigin.CHAT, mock(AgentExecutionPlan.class)),
                        null,
                        provider(RunOrigin.WORKBENCH, mock(AgentExecutionPlan.class)))));

        assertEquals("execution plan providers must not contain null", error.getMessage());
    }

    private ExecutionPlanProvider provider(
            final RunOrigin supportedOrigin, final AgentExecutionPlan plan) {
        return new ExecutionPlanProvider() {
            @Override
            public boolean supports(RunOrigin origin) {
                return supportedOrigin == origin;
            }

            @Override
            public AgentExecutionPlan prepare(ChatRun run) {
                return plan;
            }
        };
    }

    private ChatRun chatRun() {
        return ChatRun.submit(ChatRunId.of("chat-run"), "chat-session", 1L,
                "chat-key", false, NOW);
    }

    private ChatRun workbenchRun() {
        return ChatRun.submit(ChatRunId.of("workbench-run"), "workbench-session", 2L,
                "workbench-key", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:IMPLEMENT_TEST", "workbench-run"), NOW);
    }
}
