package com.example.agentweb.domain.refinery;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
class ConversationTurnTest {

    @Test
    void should_hold_role_content_timestamp() {
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        ConversationTurn turn = new ConversationTurn("user", "hello", t);

        assertEquals("user", turn.getRole());
        assertEquals("hello", turn.getContent());
        assertEquals(t, turn.getTimestamp());
    }

    @Test
    void null_role_is_normalized_to_empty_string() {
        ConversationTurn turn = new ConversationTurn(null, "x", Instant.EPOCH);
        assertEquals("", turn.getRole());
    }

    @Test
    void null_content_is_normalized_to_empty_string() {
        ConversationTurn turn = new ConversationTurn("user", null, Instant.EPOCH);
        assertEquals("", turn.getContent());
    }

    @Test
    void null_timestamp_should_be_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationTurn("user", "x", null));
    }
}
