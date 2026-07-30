package com.example.agentweb.domain.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentType} 稳定身份解析单测。
 *
 * @author alex
 * @since 2026-06-25
 */
class AgentTypeTest {

    @Test
    void parseKnown_validCaseInsensitiveWithWhitespace_shouldReturnType() {
        assertEquals(AgentType.CLAUDE, AgentType.parseKnown("CLAUDE"));
        assertEquals(AgentType.CODEX, AgentType.parseKnown("codex"));
        assertEquals(AgentType.NATIVE, AgentType.parseKnown(" native "));
    }

    @Test
    void parseKnown_nullOrBlank_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> AgentType.parseKnown(null));
        assertThrows(IllegalArgumentException.class, () -> AgentType.parseKnown("   "));
    }

    @Test
    void parseKnown_unknown_shouldThrow() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AgentType.parseKnown("gemini"));
        assertTrue(ex.getMessage().contains("gemini"));
    }

    @Test
    void parseKnown_removedCursor_shouldThrow() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AgentType.parseKnown("cursor"));
        assertTrue(ex.getMessage().contains("cursor"));
    }
}
