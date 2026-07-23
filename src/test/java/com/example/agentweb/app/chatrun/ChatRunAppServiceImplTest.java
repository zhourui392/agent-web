package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class ChatRunAppServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");

    private SessionRepository sessionRepository;
    private ChatRunRepository runRepository;
    private ChatRunEventStore eventStore;
    private ChatRunEventHub eventHub;
    private ChatRunLauncher launcher;
    private ChatRunQueryService queryService;
    private ChatRunStreamSettings settings;
    private ChatRunActivityGuard activityGuard;
    private AgentGateway gateway;
    private ChatRunAppServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        runRepository = mock(ChatRunRepository.class);
        eventStore = mock(ChatRunEventStore.class);
        eventHub = mock(ChatRunEventHub.class);
        launcher = mock(ChatRunLauncher.class);
        queryService = mock(ChatRunQueryService.class);
        settings = mock(ChatRunStreamSettings.class);
        when(settings.getMaxActiveRuns()).thenReturn(8);
        activityGuard = mock(ChatRunActivityGuard.class);
        gateway = mock(AgentGateway.class);
        ChatRunEventAppender appender = new ChatRunEventAppender(
                runRepository, eventStore, eventHub, new AfterCommitExecutor());
        ChatRunIdGenerator idGenerator = mock(ChatRunIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(ChatRunId.of("run-1"));
        service = new ChatRunAppServiceImpl(sessionRepository, runRepository, eventStore, appender,
                launcher, queryService, gateway, idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC), settings, activityGuard, action -> action.get());
    }

    @Test
    void submit_should_persist_user_run_and_first_event_before_launch() {
        ChatSession session = session("session-1");
        when(sessionRepository.findById("session-1")).thenReturn(session);
        when(runRepository.findBySessionAndIdempotencyKey("session-1", "key-1"))
                .thenReturn(Optional.<ChatRun>empty());
        when(sessionRepository.addMessageReturningId(eq("session-1"), any())).thenReturn(11L);
        when(eventStore.appendAssigned(eq(ChatRunId.of("run-1")), any(), anyList(), eq(NOW)))
                .thenAnswer(invocation -> Collections.singletonList(
                        new ChatRunEvent(ChatRunId.of("run-1"), 1L, "run_status",
                                "{\"status\":\"PENDING\"}", 20, NOW)));

        ChatRunSubmission result = service.submit(new SubmitChatRunCommand(
                "session-1", "question", null, true, "key-1"));

        assertEquals("run-1", result.getRunId());
        assertEquals(ChatRunStatus.PENDING, result.getStatus());
        assertEquals(1L, result.getLastEventSeq());
        assertFalse(result.isDuplicated());
        InOrder order = inOrder(sessionRepository, runRepository, eventStore, launcher);
        order.verify(sessionRepository).addMessageReturningId(eq("session-1"), any());
        order.verify(runRepository).add(any(ChatRun.class));
        order.verify(eventStore).appendAssigned(eq(ChatRunId.of("run-1")), any(), anyList(), eq(NOW));
        order.verify(launcher).launch(ChatRunId.of("run-1"));
    }

    @Test
    void submit_with_same_idempotency_key_should_return_existing_without_side_effects() {
        when(sessionRepository.findById("session-1")).thenReturn(session("session-1"));
        ChatRun existing = ChatRun.submit(ChatRunId.of("existing"), "session-1", 10L, "key-1", NOW);
        when(runRepository.findBySessionAndIdempotencyKey("session-1", "key-1"))
                .thenReturn(Optional.of(existing));

        ChatRunSubmission result = service.submit(new SubmitChatRunCommand(
                "session-1", "question", null, true, "key-1"));

        assertEquals("existing", result.getRunId());
        assertTrue(result.isDuplicated());
        verify(sessionRepository, never()).addMessageReturningId(any(), any());
        verify(runRepository, never()).add(any());
        verify(launcher, never()).launch(any());
    }

    @Test
    void submit_with_active_run_should_reject_before_writing_message() {
        when(sessionRepository.findById("session-1")).thenReturn(session("session-1"));
        when(runRepository.findBySessionAndIdempotencyKey("session-1", "key-2"))
                .thenReturn(Optional.<ChatRun>empty());
        org.mockito.Mockito.doThrow(new ActiveChatRunExistsException("session-1"))
                .when(activityGuard).requireInactive("session-1");

        assertThrows(ActiveChatRunExistsException.class, () -> service.submit(
                new SubmitChatRunCommand("session-1", "question", null, true, "key-2")));

        verify(sessionRepository, never()).addMessageReturningId(any(), any());
        verify(runRepository, never()).findActiveBySessionId(any());
    }

    @Test
    void submit_when_global_capacity_is_reached_should_reject_before_writing_message() {
        when(sessionRepository.findById("session-1")).thenReturn(session("session-1"));
        when(runRepository.findBySessionAndIdempotencyKey("session-1", "key-2"))
                .thenReturn(Optional.<ChatRun>empty());
        when(queryService.countActiveRuns()).thenReturn(8L);

        assertThrows(RunCapacityExceededException.class, () -> service.submit(
                new SubmitChatRunCommand("session-1", "question", null, true, "key-2")));

        verify(sessionRepository, never()).addMessageReturningId(any(), any());
        verify(runRepository, never()).add(any());
    }

    @Test
    void find_should_hide_run_when_owning_session_is_invisible() {
        ChatRun run = ChatRun.submit(ChatRunId.of("run-1"), "other-session", 10L, "key", NOW);
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));
        when(sessionRepository.findById("other-session")).thenReturn(null);

        assertThrows(ChatRunNotFoundException.class, () -> service.find("run-1"));
    }

    @Test
    void stop_running_run_should_persist_cancel_request_then_stop_process_after_commit() {
        ChatRun run = ChatRun.submit(ChatRunId.of("run-1"), "session-1", 10L, "key", NOW.minusSeconds(2));
        run.start(NOW.minusSeconds(1));
        when(runRepository.findById(ChatRunId.of("run-1"))).thenReturn(Optional.of(run));
        when(sessionRepository.findById("session-1")).thenReturn(session("session-1"));
        when(eventStore.appendAssigned(eq(ChatRunId.of("run-1")), any(), anyList(), eq(NOW)))
                .thenReturn(Collections.<ChatRunEvent>emptyList());

        ChatRunView result = service.stop("run-1");

        assertEquals(ChatRunStatus.CANCEL_REQUESTED, result.getStatus());
        ArgumentCaptor<ChatRun> saved = ArgumentCaptor.forClass(ChatRun.class);
        verify(runRepository).update(saved.capture());
        assertEquals(ChatRunStatus.CANCEL_REQUESTED, saved.getValue().getStatus());
        verify(gateway).stopStream("run-1");
    }

    private ChatSession session(String id) {
        return new ChatSession(id, AgentType.CODEX, "/workspace", NOW,
                Collections.emptyList());
    }
}
