package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.refinery.ConversationTurn;
import com.example.agentweb.domain.refinery.ConversationView;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.VerdictSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
class ChatViewBuilderTest {

    private final SessionRepository sessionRepo = mock(SessionRepository.class);
    private final ChatViewBuilder builder = new ChatViewBuilder(sessionRepo);

    @Test
    void should_map_chat_session_to_view() {
        ChatSession session = new ChatSession(
                "sess-1", AgentType.CLAUDE, "/tmp",
                Instant.parse("2026-06-02T10:00:00Z"),
                Arrays.asList(
                        new ChatMessage("user", "hello", Instant.parse("2026-06-02T10:00:01Z")),
                        new ChatMessage("assistant", "hi", Instant.parse("2026-06-02T10:00:02Z"))
                )
        );
        when(sessionRepo.findById("sess-1")).thenReturn(session);

        Optional<ConversationView> result = builder.build("sess-1");

        assertTrue(result.isPresent());
        ConversationView view = result.get();
        assertEquals("sess-1", view.getSourceId());
        assertEquals(SourceType.CHAT, view.getSourceType());
        assertEquals(AgentType.CLAUDE, view.getAgentType());
        assertEquals("/tmp", view.getWorkingDir());
        assertEquals("unknown", view.getEnv());
        assertEquals(VerdictSignal.NONE, view.getVerdict());
        assertEquals(2, view.getTurns().size());
        ConversationTurn first = view.getTurns().get(0);
        assertEquals("user", first.getRole());
        assertEquals("hello", first.getContent());
        assertEquals(Instant.parse("2026-06-02T10:00:01Z"), first.getTimestamp());
    }

    @Test
    void should_propagate_session_env_when_present() {
        ChatSession session = new ChatSession(
                "sess-2", AgentType.CLAUDE, "/tmp",
                Instant.parse("2026-06-02T10:00:00Z"),
                Arrays.asList(new ChatMessage("user", "x", Instant.parse("2026-06-02T10:00:01Z")))
        );
        session.setEnv("prod");
        when(sessionRepo.findById("sess-2")).thenReturn(session);

        ConversationView view = builder.build("sess-2").get();

        assertEquals("prod", view.getEnv());
    }

    @Test
    void should_return_empty_when_session_missing() {
        when(sessionRepo.findById("missing")).thenReturn(null);

        Optional<ConversationView> result = builder.build("missing");

        assertFalse(result.isPresent());
    }

    @Test
    void should_return_empty_when_session_has_no_messages() {
        ChatSession session = new ChatSession(
                "sess-empty", AgentType.CLAUDE, "/tmp",
                Instant.parse("2026-06-02T10:00:00Z"),
                java.util.Collections.<ChatMessage>emptyList()
        );
        when(sessionRepo.findById("sess-empty")).thenReturn(session);

        Optional<ConversationView> result = builder.build("sess-empty");

        assertFalse(result.isPresent());
    }
}
