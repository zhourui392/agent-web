package com.example.agentweb.app.chatrun;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.adapter.AgentStreamResult;
import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.app.refinery.RefineryRecaller;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunExecutorTest {

    private ChatRunQueryService queryService;
    private ChatRunLifecycleService lifecycleService;
    private AgentGateway gateway;
    private SessionRepository sessionRepository;
    private ChatRunPromptBuilder promptBuilder;
    private ChatRunEventBufferFactory eventBufferFactory;
    private ChatRunEventBuffer eventBuffer;
    private ChatRunExecutor executor;

    @BeforeEach
    void setUp() {
        queryService = mock(ChatRunQueryService.class);
        lifecycleService = mock(ChatRunLifecycleService.class);
        gateway = mock(AgentGateway.class);
        sessionRepository = mock(SessionRepository.class);
        promptBuilder = mock(ChatRunPromptBuilder.class);
        eventBufferFactory = mock(ChatRunEventBufferFactory.class);
        eventBuffer = mock(ChatRunEventBuffer.class);
        when(eventBufferFactory.open(eq(ChatRunId.of("run-1")), any())).thenReturn(eventBuffer);
        Executor directExecutor = new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        executor = new ChatRunExecutor(directExecutor, queryService, lifecycleService,
                gateway, sessionRepository, promptBuilder, eventBufferFactory,
                Optional.<RefineryRecaller>empty(), Optional.<RecallObservationRecorder>empty());
    }

    @Test
    void launch_should_start_once_stream_normalized_chunks_and_complete() throws Exception {
        ChatRunExecutionContext context = context();
        when(queryService.findExecutionContext("run-1")).thenReturn(Optional.of(context));
        when(promptBuilder.prepare(context, "question")).thenReturn("prepared prompt");
        when(gateway.extractResumeId(AgentType.CODEX, "raw")).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "raw"))
                .thenReturn(Collections.singletonList("normalized"));
        doAnswer(invocation -> {
            Consumer<String> chunk = invocation.getArgument(7);
            Consumer<AgentStreamResult> exit = invocation.getArgument(8);
            chunk.accept("raw");
            exit.accept(AgentStreamResult.completed(0));
            return null;
        }).when(gateway).runStreamWithResult(eq(AgentType.CODEX), eq("/workspace"), eq("prepared prompt"),
                eq("run-1"), eq("resume-1"), eq("test"), eq(0L), any(), any(), eq("user-1"), eq(null));

        executor.launch(ChatRunId.of("run-1"));

        org.mockito.InOrder order = inOrder(lifecycleService, gateway, eventBuffer);
        order.verify(lifecycleService).start(ChatRunId.of("run-1"));
        order.verify(gateway).runStreamWithResult(eq(AgentType.CODEX), eq("/workspace"), eq("prepared prompt"),
                eq("run-1"), eq("resume-1"), eq("test"), eq(0L), any(), any(), eq("user-1"), eq(null));
        order.verify(eventBuffer).append("chunk", "normalized");
        order.verify(eventBuffer).flush();
        order.verify(lifecycleService).complete(ChatRunId.of("run-1"), "normalized",
                AgentStreamResult.completed(0), null);
        verify(eventBuffer).close();
    }

    @Test
    void execution_failure_should_finish_run_without_retrying_cli() throws Exception {
        ChatRunExecutionContext context = context();
        when(queryService.findExecutionContext("run-1")).thenReturn(Optional.of(context));
        when(promptBuilder.prepare(context, "question")).thenReturn("prepared prompt");
        doThrow(new IOException("spawn failed")).when(gateway).runStreamWithResult(any(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any(), any(), any());

        executor.launch(ChatRunId.of("run-1"));

        verify(lifecycleService).fail(ChatRunId.of("run-1"), "EXECUTION_FAILED",
                "Agent 执行失败，请稍后重试", null);
    }

    @Test
    void rejected_background_submission_should_fail_persisted_run_instead_of_leaving_it_pending() {
        Executor rejectingExecutor = new Executor() {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("executor saturated");
            }
        };
        ChatRunExecutor rejecting = new ChatRunExecutor(rejectingExecutor, queryService, lifecycleService,
                gateway, sessionRepository, promptBuilder, eventBufferFactory,
                Optional.<RefineryRecaller>empty(), Optional.<RecallObservationRecorder>empty());

        rejecting.launch(ChatRunId.of("run-1"));

        verify(lifecycleService).fail(ChatRunId.of("run-1"), "RUN_LAUNCH_REJECTED",
                "任务排队失败，请稍后重试", null);
    }

    private ChatRunExecutionContext context() {
        return new ChatRunExecutionContext("run-1", "session-1", 11L, AgentType.CODEX,
                "/workspace", "resume-1", "test", "user-1", "question", false,
                Collections.<ChatRunHistoryMessageView>emptyList());
    }
}
