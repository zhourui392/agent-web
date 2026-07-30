package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.app.refinery.RecallObservationStart;
import com.example.agentweb.app.refinery.RecallStats;
import com.example.agentweb.app.refinery.RecallStatus;
import com.example.agentweb.app.refinery.RecallTrace;
import com.example.agentweb.app.refinery.RefineryRecaller;
import com.example.agentweb.app.refinery.ScoredRecallHit;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatRun 召回观测编排测试，确保观测端口失败不会阻断主执行链路。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunExecutorRecallObservationTest {

    private ChatRunQueryService queryService;
    private ChatRunLifecycleService lifecycleService;
    private AgentGateway gateway;
    private ChatRunPromptBuilder promptBuilder;
    private ChatRunEventBufferFactory eventBufferFactory;
    private ChatRunEventBuffer eventBuffer;
    private RefineryRecaller recaller;
    private RecallObservationRecorder recorder;
    private ChatRunExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        queryService = mock(ChatRunQueryService.class);
        lifecycleService = mock(ChatRunLifecycleService.class);
        gateway = mock(AgentGateway.class);
        promptBuilder = mock(ChatRunPromptBuilder.class);
        eventBufferFactory = mock(ChatRunEventBufferFactory.class);
        eventBuffer = mock(ChatRunEventBuffer.class);
        recaller = mock(RefineryRecaller.class);
        recorder = mock(RecallObservationRecorder.class);
        when(gateway.historyDeliveryMode(AgentType.CODEX)).thenReturn(HistoryDeliveryMode.PROMPT_PREFIX);
        when(eventBufferFactory.open(eq(ChatRunId.of("run-1")), any())).thenReturn(eventBuffer);
        when(lifecycleService.complete(eq(ChatRunId.of("run-1")), anyString(),
                any(AgentExecutionResult.class), any()))
                .thenReturn(20L);
        doAnswer(invocation -> {
            Consumer<AgentExecutionResult> exit = invocation.getArgument(2);
            exit.accept(AgentExecutionResult.fromStream(AgentStreamResult.completed(0)));
            return null;
        }).when(gateway).runStreamWithResult(any(AgentRunInvocation.class), any(), any());
        Executor direct = Runnable::run;
        ChatToolInvocationTrackerFactory trackerFactory = mock(ChatToolInvocationTrackerFactory.class);
        ChatToolInvocationTrackerFactory.Tracker tracker = mock(ChatToolInvocationTrackerFactory.Tracker.class);
        when(trackerFactory.open(anyString(), anyString(), any())).thenReturn(tracker);
        executor = new ChatRunExecutor(direct, queryService, lifecycleService, gateway,
                mock(SessionRepository.class), promptBuilder, eventBufferFactory, trackerFactory,
                Optional.of(recaller), Optional.of(recorder));
    }

    @Test
    void recallHit_shouldRecordTracePublishPublicEventAndAttachAssistant() {
        ChatRunExecutionContext context = context(true);
        RecallTrace trace = hitTrace();
        when(queryService.findExecutionContext("run-1")).thenReturn(Optional.of(context));
        when(recorder.tryCreateStart(any())).thenReturn(Optional.of("attempt-1"));
        when(recaller.traceForChat("退款问题", "/workspace")).thenReturn(trace);
        when(promptBuilder.prepareDetailed(context, "augmented prompt", HistoryDeliveryMode.PROMPT_PREFIX))
                .thenReturn(new PreparedChatRunPrompt("prepared prompt", null));

        executor.launch(ChatRunId.of("run-1"));

        ArgumentCaptor<RecallObservationStart> start = ArgumentCaptor.forClass(RecallObservationStart.class);
        verify(recorder).tryCreateStart(start.capture());
        assertEquals("session-1", start.getValue().getSessionId());
        assertEquals(11L, start.getValue().getUserMessageId());
        assertEquals(RecallStatus.PENDING, start.getValue().getStatus());
        verify(recorder).tryRecordTrace("attempt-1", trace);
        verify(promptBuilder).prepareDetailed(context, "augmented prompt", HistoryDeliveryMode.PROMPT_PREFIX);
        verify(recorder).tryAttachAssistantMessage("attempt-1", 20L);

        ArgumentCaptor<String> recallJson = ArgumentCaptor.forClass(String.class);
        verify(eventBuffer).append(eq("recall"), recallJson.capture());
        assertTrue(recallJson.getValue().contains("\"status\":\"HIT\""));
        assertTrue(recallJson.getValue().contains("\"hits\""));
        assertFalse(recallJson.getValue().contains("chunk-1"));
        assertFalse(recallJson.getValue().contains("finalScore"));
    }

    @Test
    void recallDisabled_shouldCreateSkippedAttemptWithoutCallingRecaller() throws Exception {
        ChatRunExecutionContext context = context(false);
        when(queryService.findExecutionContext("run-1")).thenReturn(Optional.of(context));
        when(recorder.tryCreateStart(any())).thenReturn(Optional.of("attempt-disabled"));
        when(promptBuilder.prepareDetailed(context, "退款问题", HistoryDeliveryMode.PROMPT_PREFIX))
                .thenReturn(new PreparedChatRunPrompt("退款问题", null));

        executor.launch(ChatRunId.of("run-1"));

        ArgumentCaptor<RecallObservationStart> start = ArgumentCaptor.forClass(RecallObservationStart.class);
        verify(recorder).tryCreateStart(start.capture());
        assertEquals(RecallStatus.SKIPPED, start.getValue().getStatus());
        assertEquals("DISABLED_BY_CLIENT", start.getValue().getSkipReason());
        verify(recaller, never()).traceForChat(anyString(), anyString());
        verify(recorder, never()).tryRecordTrace(anyString(), any(RecallTrace.class));
        ArgumentCaptor<AgentRunInvocation> invocation = ArgumentCaptor.forClass(AgentRunInvocation.class);
        verify(gateway).runStreamWithResult(invocation.capture(), any(), any());
        assertEquals("退款问题", invocation.getValue().getPrompt());
    }

    @Test
    void createAttemptFailure_shouldNotBlockRecallOrAgentExecution() throws Exception {
        ChatRunExecutionContext context = context(true);
        RecallTrace trace = hitTrace();
        when(queryService.findExecutionContext("run-1")).thenReturn(Optional.of(context));
        when(recorder.tryCreateStart(any())).thenThrow(new IllegalStateException("observation db down"));
        when(recaller.traceForChat("退款问题", "/workspace")).thenReturn(trace);
        when(promptBuilder.prepareDetailed(context, "augmented prompt", HistoryDeliveryMode.PROMPT_PREFIX))
                .thenReturn(new PreparedChatRunPrompt("prepared prompt", null));

        executor.launch(ChatRunId.of("run-1"));

        verify(recaller).traceForChat("退款问题", "/workspace");
        ArgumentCaptor<AgentRunInvocation> invocation = ArgumentCaptor.forClass(AgentRunInvocation.class);
        verify(gateway).runStreamWithResult(invocation.capture(), any(), any());
        assertEquals("prepared prompt", invocation.getValue().getPrompt());
        verify(lifecycleService, never()).fail(any(), anyString(), anyString(), any());
    }

    @Test
    void traceAndAttachFailures_shouldNotTurnCompletedRunIntoFailure() throws Exception {
        ChatRunExecutionContext context = context(true);
        RecallTrace trace = hitTrace();
        when(queryService.findExecutionContext("run-1")).thenReturn(Optional.of(context));
        when(recorder.tryCreateStart(any())).thenReturn(Optional.of("attempt-1"));
        when(recaller.traceForChat("退款问题", "/workspace")).thenReturn(trace);
        when(promptBuilder.prepareDetailed(context, "augmented prompt", HistoryDeliveryMode.PROMPT_PREFIX))
                .thenReturn(new PreparedChatRunPrompt("prepared prompt", null));
        doThrow(new IllegalStateException("trace write failed"))
                .when(recorder).tryRecordTrace("attempt-1", trace);
        doThrow(new IllegalStateException("attach write failed"))
                .when(recorder).tryAttachAssistantMessage("attempt-1", 20L);

        executor.launch(ChatRunId.of("run-1"));

        ArgumentCaptor<AgentRunInvocation> invocation = ArgumentCaptor.forClass(AgentRunInvocation.class);
        verify(gateway).runStreamWithResult(invocation.capture(), any(), any());
        assertEquals("prepared prompt", invocation.getValue().getPrompt());
        verify(lifecycleService).complete(eq(ChatRunId.of("run-1")), anyString(),
                any(AgentExecutionResult.class), any());
        verify(lifecycleService, never()).fail(any(), anyString(), anyString(), any());
    }

    private ChatRunExecutionContext context(boolean recallEnabled) {
        return new ChatRunExecutionContext("run-1", "session-1", 11L, AgentType.CODEX,
                "/workspace", null, "test", "user-1", "退款问题", recallEnabled,
                Collections.<ChatRunHistoryMessageView>emptyList());
    }

    private RecallTrace hitTrace() {
        return RecallTrace.hit("退款问题", "augmented prompt",
                Collections.singletonList(new ScoredRecallHit("chunk-1", 1, "title", "conclusion",
                        "source-s1", "1-2", 0.9d, 0.8d, 0.1d, 0.2d,
                        "qwen", "CHAT", "EXPLORATORY", "test", 0.8d, Instant.now())),
                new RecallStats(1, 1, 0, 1, 0, 0.8d, 0.9d, 3,
                        false, false, 30d, 0.6d, 0d, 0d, 0.7d, 0.2d, 0.1d,
                        "qwen", 1024), 9L);
    }
}
