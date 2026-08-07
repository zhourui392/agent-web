package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider-neutral runtime routing and cancellation race tests.
 *
 * @author alex
 * @since 2026-07-29
 */
class RoutingAgentGatewayTest {

    private AgentRuntime cli;
    private AgentRuntime nativeRuntime;
    private RoutingAgentGateway gateway;

    @BeforeEach
    void setUp() {
        cli = runtime(AgentType.CODEX, AgentType.CLAUDE);
        nativeRuntime = runtime(AgentType.NATIVE);
        when(nativeRuntime.historyDeliveryMode()).thenReturn(HistoryDeliveryMode.TYPED);
        gateway = new RoutingAgentGateway(Arrays.asList(cli, nativeRuntime));
    }

    @Test
    void nativeInvocation_shouldRouteToNativeRuntime() throws Exception {
        AgentRunInvocation invocation = invocation(AgentType.NATIVE);
        doAnswer(call -> {
            Consumer<AgentExecutionResult> complete = call.getArgument(2);
            complete.accept(AgentExecutionResult.fromStream(AgentStreamResult.completed(0)));
            return null;
        }).when(nativeRuntime).run(any(), any(), any());
        AtomicReference<AgentExecutionResult> result = new AtomicReference<AgentExecutionResult>();

        gateway.runStreamWithResult(invocation, ignored -> { }, result::set);

        verify(nativeRuntime).run(any(), any(), any());
        verify(cli, never()).run(any(), any(), any());
        assertEquals(0, result.get().getStreamResult().getExitCode());
        assertEquals(HistoryDeliveryMode.TYPED,
                gateway.historyDeliveryMode(AgentType.NATIVE));
    }

    @Test
    void cancellationBeforeRuntimeRegistration_shouldNotStartProvider() throws Exception {
        AgentRunInvocation invocation = invocation(AgentType.NATIVE);
        AtomicReference<AgentExecutionResult> result = new AtomicReference<AgentExecutionResult>();

        gateway.stopStream("run-1");
        gateway.runStreamWithResult(invocation, ignored -> { }, result::set);

        verify(nativeRuntime, never()).run(any(), any(), any());
        assertEquals(-1, result.get().getStreamResult().getExitCode());
        assertEquals("STOPPED", result.get().getProviderExitReason());
    }

    @Test
    void stopDuringRun_shouldReachRegisteredRuntime() throws Exception {
        doAnswer(call -> {
            gateway.stopStream("run-1");
            Consumer<AgentExecutionResult> complete = call.getArgument(2);
            complete.accept(AgentExecutionResult.stopped());
            return null;
        }).when(nativeRuntime).run(any(), any(), any());

        gateway.runStreamWithResult(invocation(AgentType.NATIVE), ignored -> { }, ignored -> { });

        verify(nativeRuntime).stop("run-1");
    }

    @Test
    void stopAfterTerminal_shouldBeIdempotentWithoutLeavingPendingCancellation() throws Exception {
        doAnswer(call -> {
            Consumer<AgentExecutionResult> complete = call.getArgument(2);
            complete.accept(AgentExecutionResult.fromStream(AgentStreamResult.completed(0)));
            return null;
        }).when(nativeRuntime).run(any(), any(), any());
        AtomicReference<AgentExecutionResult> secondResult =
                new AtomicReference<AgentExecutionResult>();

        gateway.runStreamWithResult(invocation(AgentType.NATIVE),
                ignored -> { }, ignored -> { });
        gateway.stopStream("run-1");
        gateway.runStreamWithResult(invocation(AgentType.NATIVE),
                ignored -> { }, secondResult::set);

        verify(nativeRuntime, times(2)).run(any(), any(), any());
        assertEquals(0, secondResult.get().getStreamResult().getExitCode());
    }

    @Test
    void nativeOutputContract_shouldPassThroughAndNeverExtractResumeId() {
        when(nativeRuntime.normalizeChunk(AgentType.NATIVE, "line"))
                .thenReturn(Collections.singletonList("line"));
        when(nativeRuntime.extractResumeId(AgentType.NATIVE, "line")).thenReturn(null);

        assertEquals(Collections.singletonList("line"),
                gateway.normalizeChunk(AgentType.NATIVE, "line"));
        assertEquals(null, gateway.extractResumeId(AgentType.NATIVE, "line"));
    }

    @Test
    void lateChunksAndDuplicateTerminalCallbacks_shouldBeIgnored() throws Exception {
        doAnswer(call -> {
            Consumer<String> chunk = call.getArgument(1);
            Consumer<AgentExecutionResult> complete = call.getArgument(2);
            chunk.accept("before-terminal");
            complete.accept(AgentExecutionResult.fromStream(AgentStreamResult.completed(0)));
            chunk.accept("late-chunk");
            complete.accept(AgentExecutionResult.fromStream(AgentStreamResult.completed(1)));
            return null;
        }).when(nativeRuntime).run(any(), any(), any());
        AtomicInteger chunks = new AtomicInteger();
        AtomicInteger terminals = new AtomicInteger();

        gateway.runStreamWithResult(invocation(AgentType.NATIVE),
                ignored -> chunks.incrementAndGet(), ignored -> terminals.incrementAndGet());

        assertEquals(1, chunks.get());
        assertEquals(1, terminals.get());
    }

    private AgentRuntime runtime(AgentType... types) {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.supportedTypes()).thenReturn(
                Collections.unmodifiableSet(new java.util.LinkedHashSet<AgentType>(
                        Arrays.asList(types))));
        when(runtime.historyDeliveryMode()).thenReturn(HistoryDeliveryMode.PROMPT_PREFIX);
        return runtime;
    }

    private AgentRunInvocation invocation(AgentType type) {
        return AgentRunInvocation.builder()
                .runId("run-1")
                .conversationId("session-1")
                .userMessageId(11L)
                .agentType(type)
                .workingDir("/workspace")
                .prompt("question")
                .resumeId(null)
                .env("test")
                .userId("user-1")
                .timeoutSeconds(0L)
                .history(Collections.emptyList())
                .extraEnv(Collections.emptyMap())
                .build();
    }
}
