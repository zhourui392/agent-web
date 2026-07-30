package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentStateCheckpointPayload;
import com.example.agentweb.app.agentrun.port.AgentUsage;
import com.example.agentweb.app.common.AfterCommitExecutor;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpoint;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T10:00:02Z");

    private ChatRunRepository runRepository;
    private SessionRepository sessionRepository;
    private ChatRunEventStore eventStore;
    private DiagnosisCheckpointRepository checkpointRepository;
    private ChatRunLifecycleService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(ChatRunRepository.class);
        sessionRepository = mock(SessionRepository.class);
        eventStore = mock(ChatRunEventStore.class);
        checkpointRepository = mock(DiagnosisCheckpointRepository.class);
        ChatRunEventHub eventHub = mock(ChatRunEventHub.class);
        ChatRunEventAppender appender = new ChatRunEventAppender(
                runRepository, eventStore, eventHub, new AfterCommitExecutor());
        service = new ChatRunLifecycleService(runRepository, sessionRepository,
                checkpointRepository, appender, Clock.fixed(NOW, ZoneOffset.UTC));
        when(eventStore.appendAssigned(any(), any(), anyList(), any()))
                .thenReturn(Collections.<ChatRunEvent>emptyList());
    }

    @Test
    void start_should_transition_and_append_running_status() {
        ChatRun run = pendingRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));

        service.start(ChatRunId.of("run-1"));

        ArgumentCaptor<ChatRun> saved = ArgumentCaptor.forClass(ChatRun.class);
        verify(runRepository).update(saved.capture());
        assertEquals(ChatRunStatus.RUNNING, saved.getValue().getStatus());
        verify(eventStore).appendAssigned(eq(ChatRunId.of("run-1")), any(), anyList(), eq(NOW));
    }

    @Test
    void complete_success_should_persist_assistant_before_success_terminal_event() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));
        when(sessionRepository.addMessageReturningId(eq("session-1"), any())).thenReturn(21L);

        service.complete(ChatRunId.of("run-1"), "normalized output", 0,
                "{\"query\":\"q\"}");

        org.mockito.InOrder order = inOrder(sessionRepository, runRepository, eventStore);
        order.verify(sessionRepository).addMessageReturningId(eq("session-1"), any());
        order.verify(sessionRepository).saveRecall(21L, "{\"query\":\"q\"}");
        order.verify(runRepository).update(any(ChatRun.class));
        order.verify(eventStore).appendAssigned(eq(ChatRunId.of("run-1")), any(), anyList(), eq(NOW));
        assertEquals(ChatRunStatus.SUCCEEDED, run.getStatus());
        assertEquals(Long.valueOf(21L), run.getAssistantMessageId());
    }

    @Test
    void complete_nativeSuccess_shouldPersistCheckpointAtAssistantBoundaryInSameOrchestration() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));
        when(sessionRepository.addMessageReturningId(eq("session-1"), any())).thenReturn(21L);
        AgentExecutionResult result = new AgentExecutionResult(
                AgentStreamResult.completed(0),
                new AgentStateCheckpointPayload("state-v1", "schema-v1"),
                new AgentUsage(100L, 30L, 8L), "SUCCESS", "");

        service.complete(ChatRunId.of("run-1"), "normalized output", result, null);

        ArgumentCaptor<DiagnosisCheckpoint> checkpoint =
                ArgumentCaptor.forClass(DiagnosisCheckpoint.class);
        org.mockito.InOrder order = inOrder(sessionRepository, checkpointRepository,
                runRepository, eventStore);
        order.verify(sessionRepository).addMessageReturningId(eq("session-1"), any());
        order.verify(checkpointRepository).save(checkpoint.capture());
        order.verify(runRepository).update(run);
        order.verify(eventStore).appendAssigned(eq(ChatRunId.of("run-1")), any(), anyList(), eq(NOW));
        assertEquals("run-1", checkpoint.getValue().getRunId());
        assertEquals("session-1", checkpoint.getValue().getSessionId());
        assertEquals(11L, checkpoint.getValue().getUserMessageId());
        assertEquals(21L, checkpoint.getValue().getAssistantMessageId());
        assertEquals("state-v1", checkpoint.getValue().getStateSnapshot());
        assertEquals("schema-v1", checkpoint.getValue().getSnapshotSchemaVersion());
        assertEquals(100L, checkpoint.getValue().getInputTokens());
        assertEquals(30L, checkpoint.getValue().getOutputTokens());
        assertEquals(8L, checkpoint.getValue().getCacheReadInputTokens());
        assertEquals(NOW, checkpoint.getValue().getCreatedAt());
    }

    @Test
    void complete_rejected_shouldUseStablePublicFailureWithoutLeakingProviderDetail() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));
        AgentExecutionResult result = new AgentExecutionResult(
                AgentStreamResult.completed(1), null, AgentUsage.zero(),
                "REJECTED", "api-key=secret provider detail");

        service.complete(ChatRunId.of("run-1"), "", result, null);

        assertEquals(ChatRunStatus.FAILED, run.getStatus());
        assertEquals("AGENT_REJECTED", run.getFailureCode());
        assertEquals("Agent 拒绝执行当前请求，请检查配置或稍后重试", run.getErrorMessage());
        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void complete_stoppedWithoutCancellationRequest_shouldFailInsteadOfCancelling() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));
        AgentExecutionResult result = new AgentExecutionResult(
                AgentStreamResult.completed(-1), null, AgentUsage.zero(), "STOPPED", "");

        service.complete(ChatRunId.of("run-1"), "", result, null);

        assertEquals(ChatRunStatus.FAILED, run.getStatus());
        assertEquals("AGENT_EXECUTION_ERROR", run.getFailureCode());
        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void complete_cancelled_should_not_persist_assistant() {
        ChatRun run = runningRun();
        run.requestCancellation(NOW.minusMillis(1));
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));

        service.complete(ChatRunId.of("run-1"), "", 143, null);

        assertEquals(ChatRunStatus.CANCELLED, run.getStatus());
        verify(sessionRepository, never()).addMessageReturningId(any(), any());
        verify(runRepository).update(run);
    }

    @Test
    void complete_idleTimeout_should_use_structured_failure_insteadOfInspectingOutputText() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));

        service.complete(ChatRunId.of("run-1"), "partial normalized output",
                AgentStreamResult.terminated(-1, AgentStreamResult.TerminationReason.IDLE_TIMEOUT), null);

        assertEquals(ChatRunStatus.FAILED, run.getStatus());
        assertEquals("IDLE_TIMEOUT", run.getFailureCode());
        assertEquals("Agent 长时间无输出，任务已停止", run.getErrorMessage());
        verify(sessionRepository, never()).addMessageReturningId(any(), any());
    }

    @Test
    void complete_outputLimit_should_not_dependOnSentinelBeingPresentInNormalizedOutput() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));

        service.complete(ChatRunId.of("run-1"), "",
                AgentStreamResult.terminated(137, AgentStreamResult.TerminationReason.OUTPUT_LIMIT), null);

        assertEquals(ChatRunStatus.FAILED, run.getStatus());
        assertEquals("OUTPUT_LIMIT", run.getFailureCode());
        assertEquals("输出超过上限，任务已停止", run.getErrorMessage());
    }

    @Test
    void append_chunk_should_allocate_next_sequence_without_lifecycle_branching() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));

        service.append(ChatRunId.of("run-1"), "chunk", "payload");

        assertEquals(1L, run.getLastEventSeq());
        verify(eventStore).appendAssigned(eq(ChatRunId.of("run-1")), any(), anyList(), eq(NOW));
    }

    @Test
    void append_batch_should_allocate_one_contiguous_sequence_range() {
        ChatRun run = runningRun();
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));
        List<ChatRunEventDraft> drafts = Arrays.asList(
                new ChatRunEventDraft("chunk", "one"),
                new ChatRunEventDraft("chunk", "two"));

        service.appendBatch(ChatRunId.of("run-1"), drafts);

        assertEquals(2L, run.getLastEventSeq());
        verify(eventStore).appendAssigned(eq(ChatRunId.of("run-1")), any(), eq(drafts), eq(NOW));
    }

    private ChatRun pendingRun() {
        return ChatRun.submit(ChatRunId.of("run-1"), "session-1", 11L, "key",
                NOW.minusSeconds(2));
    }

    private ChatRun runningRun() {
        ChatRun run = pendingRun();
        run.start(NOW.minusSeconds(1));
        return run;
    }
}
