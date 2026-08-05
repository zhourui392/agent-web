package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatRun 首次终态参与者的完整来源注册与中性分派测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatRunTerminalParticipantRegistryTest {

    private static final Instant TERMINAL_AT =
            Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void completeOriginMappingShouldConstructAndDispatchByRunOrigin() {
        ChatRunTerminalParticipant chat = participant(RunOrigin.CHAT);
        ChatRunTerminalParticipant workbench = participant(RunOrigin.WORKBENCH);
        ChatRunTerminalParticipantRegistry registry = assertDoesNotThrow(
                () -> new ChatRunTerminalParticipantRegistry(
                        Arrays.asList(workbench, chat)));
        ChatRun run = ChatRun.submit(
                ChatRunId.of("workbench-run"), "stage-session", 1L,
                "terminal-key", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:stage-implementation", "workbench-run"),
                TERMINAL_AT.minusSeconds(1));

        registry.onFirstTerminal(run, TERMINAL_AT);

        verify(workbench).onFirstTerminal(run.getId(), TERMINAL_AT);
        verify(chat, never()).onFirstTerminal(run.getId(), TERMINAL_AT);
    }

    @Test
    void missingOriginShouldFailFastAtConstruction() {
        ChatRunTerminalParticipant chat = participant(RunOrigin.CHAT);

        assertThrows(IllegalStateException.class,
                () -> new ChatRunTerminalParticipantRegistry(
                        Collections.singletonList(chat)));
    }

    @Test
    void duplicateOriginShouldFailFastAtConstruction() {
        ChatRunTerminalParticipant firstChat = participant(RunOrigin.CHAT);
        ChatRunTerminalParticipant secondChat = participant(RunOrigin.CHAT);
        ChatRunTerminalParticipant workbench = participant(RunOrigin.WORKBENCH);

        assertThrows(IllegalStateException.class,
                () -> new ChatRunTerminalParticipantRegistry(
                        Arrays.asList(firstChat, secondChat, workbench)));
    }

    @Test
    void nullParticipantOrNullOriginShouldFailFastAtConstruction() {
        ChatRunTerminalParticipant chat = participant(RunOrigin.CHAT);
        ChatRunTerminalParticipant workbench = participant(RunOrigin.WORKBENCH);
        ChatRunTerminalParticipant nullOrigin = mock(
                ChatRunTerminalParticipant.class);
        when(nullOrigin.origin()).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> new ChatRunTerminalParticipantRegistry(
                        Arrays.asList(chat, null, workbench)));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatRunTerminalParticipantRegistry(
                        Arrays.asList(chat, nullOrigin, workbench)));
    }

    private static ChatRunTerminalParticipant participant(RunOrigin origin) {
        ChatRunTerminalParticipant participant = mock(
                ChatRunTerminalParticipant.class);
        when(participant.origin()).thenReturn(origin);
        return participant;
    }
}
