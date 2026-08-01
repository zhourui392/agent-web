package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ChatRun 首次终态的参与者、RuntimeHandle 删除与 terminal event 原子编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatRunTerminalFinalizerTest {

    private static final Instant TERMINAL_AT =
            Instant.parse("2026-08-01T14:00:00Z");

    private ChatRunTerminalParticipantRegistry participantRegistry;
    private ChatRunRuntimeHandleStore handleStore;
    private ChatRunEventAppender eventAppender;
    private ChatRunTerminalFinalizer finalizer;

    @BeforeEach
    void setUp() {
        participantRegistry = mock(ChatRunTerminalParticipantRegistry.class);
        handleStore = mock(ChatRunRuntimeHandleStore.class);
        eventAppender = mock(ChatRunEventAppender.class);
        finalizer = new ChatRunTerminalFinalizer(
                participantRegistry, handleStore, eventAppender);
    }

    @Test
    void terminalRunShouldFinalizeParticipantHandleAndEventInStrictOrder()
            throws Exception {
        ChatRun run = failedRun();

        finalizer.finalizeFirstTerminal(run, TERMINAL_AT);

        InOrder order = inOrder(participantRegistry, handleStore, eventAppender);
        order.verify(participantRegistry).onFirstTerminal(run, TERMINAL_AT);
        order.verify(handleStore).delete(run.getId());
        ArgumentCaptor<List<ChatRunEventDraft>> drafts =
                listCaptor();
        order.verify(eventAppender).appendToExistingRun(
                eq(run), drafts.capture(), eq(TERMINAL_AT));
        assertEquals(1, drafts.getValue().size());
        ChatRunEventDraft terminal = drafts.getValue().get(0);
        assertEquals("terminal", terminal.getEventType());
        assertFailedPayload(terminal.getPayload());
    }

    @Test
    void successfulTerminalPayloadShouldIncludeAssistantMessageId() throws Exception {
        ChatRun run = runningRun();
        run.succeed(21L, 0, TERMINAL_AT);

        finalizer.finalizeFirstTerminal(run, TERMINAL_AT);

        ArgumentCaptor<List<ChatRunEventDraft>> drafts = listCaptor();
        verify(eventAppender).appendToExistingRun(
                eq(run), drafts.capture(), eq(TERMINAL_AT));
        JsonNode payload = new ObjectMapper().readTree(
                drafts.getValue().get(0).getPayload());
        assertEquals("SUCCEEDED", payload.get("status").asText());
        assertEquals(0, payload.get("exitCode").asInt());
        assertEquals(21L, payload.get("assistantMessageId").asLong());
        assertTrue(payload.get("failureCode").isNull());
        assertTrue(payload.get("errorMessage").isNull());
        assertTrue(payload.get("publicMessage").isNull());
    }

    @Test
    void nonTerminalRunShouldBeRejectedBeforeAnySideEffect() {
        ChatRun run = pendingRun();

        assertThrows(IllegalStateException.class,
                () -> finalizer.finalizeFirstTerminal(run, TERMINAL_AT));

        verifyNoInteractions(participantRegistry, handleStore, eventAppender);
    }

    @Test
    void participantFailureShouldPreventHandleDeletionAndTerminalEvent() {
        ChatRun run = failedRun();
        doThrow(new IllegalStateException("participant failed"))
                .when(participantRegistry).onFirstTerminal(run, TERMINAL_AT);

        assertThrows(IllegalStateException.class,
                () -> finalizer.finalizeFirstTerminal(run, TERMINAL_AT));

        verifyNoInteractions(handleStore, eventAppender);
    }

    @Test
    void handleDeletionFailureShouldPreventTerminalEvent() {
        ChatRun run = failedRun();
        doThrow(new IllegalStateException("handle delete failed"))
                .when(handleStore).delete(run.getId());

        assertThrows(IllegalStateException.class,
                () -> finalizer.finalizeFirstTerminal(run, TERMINAL_AT));

        verify(participantRegistry).onFirstTerminal(run, TERMINAL_AT);
        verifyNoInteractions(eventAppender);
    }

    private static void assertFailedPayload(String payloadJson) throws Exception {
        JsonNode payload = new ObjectMapper().readTree(payloadJson);
        assertEquals("FAILED", payload.get("status").asText());
        assertEquals(17, payload.get("exitCode").asInt());
        assertEquals("EXECUTION_FAILED", payload.get("failureCode").asText());
        assertEquals("execution failed safely", payload.get("errorMessage").asText());
        assertEquals("execution failed safely", payload.get("publicMessage").asText());
        assertTrue(payload.has("assistantMessageId"));
        assertTrue(payload.get("assistantMessageId").isNull());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ChatRunEventDraft>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private static ChatRun failedRun() {
        ChatRun run = runningRun();
        run.fail("EXECUTION_FAILED", "execution failed safely", 17, TERMINAL_AT);
        return run;
    }

    private static ChatRun runningRun() {
        ChatRun run = pendingRun();
        run.start(TERMINAL_AT.minusSeconds(1));
        return run;
    }

    private static ChatRun pendingRun() {
        return ChatRun.submit(
                ChatRunId.of("run-1"), "session-1", 11L,
                "terminal-key", TERMINAL_AT.minusSeconds(2));
    }
}
