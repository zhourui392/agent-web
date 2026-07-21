package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.app.refinery.RecallObservationStart;
import com.example.agentweb.app.refinery.RecallStats;
import com.example.agentweb.app.refinery.RecallStatus;
import com.example.agentweb.app.refinery.RecallTrace;
import com.example.agentweb.app.refinery.RefineryRecaller;
import com.example.agentweb.app.refinery.ScoredRecallHit;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.ChatProperties;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatAppServiceImpl recall-observation orchestration tests.
 *
 * @author codex
 * @since 2026-06-12
 */
class ChatAppServiceImplRecallObservationTest {

    private SessionCache sessionCache;
    private SessionRepository sessionRepository;
    private AgentGateway gateway;
    private SlashCommandExpander commandExpander;
    private RecallObservationRecorder recorder;
    private RefineryRecaller recaller;
    private ChatAppServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        sessionCache = mock(SessionCache.class);
        sessionRepository = mock(SessionRepository.class);
        gateway = mock(AgentGateway.class);
        commandExpander = mock(SlashCommandExpander.class);
        recorder = mock(RecallObservationRecorder.class);
        recaller = mock(RefineryRecaller.class);
        ChatProperties chatProperties = new ChatProperties();
        chatProperties.setFinalAnswerInstructionEnabled(false);
        Executor sync = Runnable::run;
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, gateway, sync,
                commandExpander, mock(StreamOutputExtractor.class), mock(AgentTypeResolver.class),
                mock(UploadPicStore.class), mock(UploadFileStore.class),
                Optional.of(recaller), Optional.of(recorder),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> Optional.empty()),
                chatProperties);
        when(commandExpander.expandIfCommand(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(gateway.extractResumeId(eq(AgentType.CLAUDE), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(eq(AgentType.CLAUDE), anyString()))
                .thenReturn(Collections.singletonList("assistant-normalized"));
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(7);
            IntConsumer onExit = inv.getArgument(8);
            onChunk.accept("raw");
            onExit.accept(0);
            return null;
        }).when(gateway).runStream(any(), anyString(), anyString(), anyString(), any(), any(),
                anyLong(), any(), any(), any());
    }

    @Test
    void streamMessage_enabledAndHit_shouldCreatePendingRecordTraceAndAttachAssistant() throws Exception {
        ChatSession session = session("s1");
        when(sessionCache.find("s1")).thenReturn(session);
        when(sessionRepository.findById("s1")).thenReturn(null);
        when(sessionRepository.addMessageReturningId(eq("s1"), any(ChatMessage.class)))
                .thenReturn(10L, 20L);
        when(recorder.tryCreateStart(any())).thenReturn(Optional.of("attempt-1"));
        RecallTrace trace = hitTrace();
        when(recaller.traceForChat(eq("退款问题"), any())).thenReturn(trace);

        service.streamMessage("s1", "退款问题", null, null, true);

        ArgumentCaptor<RecallObservationStart> startCaptor =
                ArgumentCaptor.forClass(RecallObservationStart.class);
        verify(recorder).tryCreateStart(startCaptor.capture());
        RecallObservationStart start = startCaptor.getValue();
        assertEquals("s1", start.getSessionId());
        assertEquals(10L, start.getUserMessageId());
        assertEquals("退款问题", start.getQuery());
        assertTrue(start.isRecallEnabled());
        assertEquals(RecallStatus.PENDING, start.getStatus());

        verify(recorder).tryRecordTrace("attempt-1", trace);
        verify(recorder).tryAttachAssistantMessage("attempt-1", 20L);
        ArgumentCaptor<String> recallJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionRepository).saveRecall(eq(20L), recallJsonCaptor.capture());
        String publicRecallJson = recallJsonCaptor.getValue();
        assertTrue(publicRecallJson.contains("\"status\":\"HIT\""));
        assertTrue(publicRecallJson.contains("\"hits\""));
        assertTrue(!publicRecallJson.contains("chunk-1"));
        assertTrue(!publicRecallJson.contains("finalScore"));
        assertTrue(!publicRecallJson.contains("attempt"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), promptCaptor.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        assertEquals("augmented prompt", promptCaptor.getValue());
    }

    @Test
    void streamMessage_recallDisabled_shouldCreateSkippedAttemptAndNotCallRecaller() throws Exception {
        ChatSession session = session("s1");
        when(sessionCache.find("s1")).thenReturn(session);
        when(sessionRepository.findById("s1")).thenReturn(null);
        when(sessionRepository.addMessageReturningId(eq("s1"), any(ChatMessage.class)))
                .thenReturn(10L, 20L);
        when(recorder.tryCreateStart(any())).thenReturn(Optional.of("attempt-disabled"));

        service.streamMessage("s1", "hi", null, null, false);

        ArgumentCaptor<RecallObservationStart> startCaptor =
                ArgumentCaptor.forClass(RecallObservationStart.class);
        verify(recorder).tryCreateStart(startCaptor.capture());
        assertEquals(RecallStatus.SKIPPED, startCaptor.getValue().getStatus());
        assertEquals("DISABLED_BY_CLIENT", startCaptor.getValue().getSkipReason());
        verify(recaller, never()).traceForChat(anyString(), any());
        verify(recorder, never()).tryRecordTrace(anyString(), any(RecallTrace.class));
        verify(recorder).tryAttachAssistantMessage("attempt-disabled", 20L);
    }

    @Test
    void streamMessage_traceNoHit_shouldRecordTraceAndSendOriginalPrompt() throws Exception {
        ChatSession session = session("s1");
        when(sessionCache.find("s1")).thenReturn(session);
        when(sessionRepository.findById("s1")).thenReturn(null);
        when(sessionRepository.addMessageReturningId(eq("s1"), any(ChatMessage.class)))
                .thenReturn(10L, 20L);
        when(recorder.tryCreateStart(any())).thenReturn(Optional.of("attempt-no-hit"));
        RecallTrace trace = RecallTrace.noHit("hi", "hi", stats(), 5L);
        when(recaller.traceForChat(eq("hi"), any())).thenReturn(trace);

        service.streamMessage("s1", "hi", null, null, true);

        verify(recorder).tryRecordTrace("attempt-no-hit", trace);
        verify(sessionRepository, never()).saveRecall(anyLong(), anyString());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), promptCaptor.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        assertEquals("hi", promptCaptor.getValue());
    }

    @Test
    void streamMessage_createAttemptFailure_shouldStillCallGateway() throws Exception {
        ChatSession session = session("s1");
        when(sessionCache.find("s1")).thenReturn(session);
        when(sessionRepository.findById("s1")).thenReturn(null);
        when(sessionRepository.addMessageReturningId(eq("s1"), any(ChatMessage.class)))
                .thenReturn(10L, 20L);
        when(recorder.tryCreateStart(any())).thenThrow(new IllegalStateException("db down"));
        RecallTrace trace = hitTrace();
        when(recaller.traceForChat(eq("退款问题"), any())).thenReturn(trace);

        service.streamMessage("s1", "退款问题", null, null, true);

        verify(recaller).traceForChat(eq("退款问题"), any());
        verify(recorder, never()).tryRecordTrace(anyString(), any(RecallTrace.class));
        verify(sessionRepository).saveRecall(eq(20L), anyString());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), promptCaptor.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        assertEquals("augmented prompt", promptCaptor.getValue());
    }

    @Test
    void streamMessage_recordTraceFailure_shouldStillCallGatewayAndPersistPublicRecall() throws Exception {
        ChatSession session = session("s1");
        when(sessionCache.find("s1")).thenReturn(session);
        when(sessionRepository.findById("s1")).thenReturn(null);
        when(sessionRepository.addMessageReturningId(eq("s1"), any(ChatMessage.class)))
                .thenReturn(10L, 20L);
        when(recorder.tryCreateStart(any())).thenReturn(Optional.of("attempt-1"));
        RecallTrace trace = hitTrace();
        when(recaller.traceForChat(eq("退款问题"), any())).thenReturn(trace);
        doThrow(new IllegalStateException("db down")).when(recorder).tryRecordTrace("attempt-1", trace);

        service.streamMessage("s1", "退款问题", null, null, true);

        verify(recorder).tryRecordTrace("attempt-1", trace);
        verify(sessionRepository).saveRecall(eq(20L), anyString());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), promptCaptor.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        assertEquals("augmented prompt", promptCaptor.getValue());
    }

    @Test
    void truncateFrom_shouldBestEffortDeleteRecallObservationRange() {
        ChatSession session = new ChatSession("s1", AgentType.CLAUDE, "/tmp",
                Instant.now(), Collections.singletonList(new ChatMessage(10L, "user", "q", Instant.now())));
        when(sessionRepository.findById("s1")).thenReturn(session);
        when(sessionRepository.truncateFrom("s1", 10L)).thenReturn(1);

        service.truncateFrom("s1", 10L);

        verify(recorder).tryDeleteByMessageRange("s1", 10L);
    }

    @Test
    void deleteSession_shouldBestEffortDeleteRecallObservationBySession() {
        when(sessionRepository.findById("s1")).thenReturn(null);

        service.deleteSession("s1");

        verify(recorder).tryDeleteBySessionId("s1");
    }

    private ChatSession session(String id) {
        ChatSession session = new ChatSession(id, AgentType.CLAUDE, "/tmp",
                Instant.now(), Collections.emptyList());
        session.setEnv("test");
        return session;
    }

    private RecallTrace hitTrace() {
        return RecallTrace.hit("退款问题", "augmented prompt",
                Collections.singletonList(new ScoredRecallHit("chunk-1", 1, "title", "conclusion",
                        "source-s1", "1-2", 0.9d, 0.8d, 0.1d, 0.2d,
                        "qwen", "CHAT", "EXPLORATORY", "test", 0.8d, Instant.now())),
                stats(), 9L);
    }

    private RecallStats stats() {
        return new RecallStats(1, 1, 0, 1, 0, 0.8d, 0.9d, 3,
                false, false, 30d, 0.6d, 0d, 0d, 0.7d, 0.2d, 0.1d,
                "qwen", 1024);
    }
}
