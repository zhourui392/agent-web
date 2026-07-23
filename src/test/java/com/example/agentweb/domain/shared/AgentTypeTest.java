package com.example.agentweb.domain.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentType} 领域规则单测:用户可选 agent 的判定与解析。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
class AgentTypeTest {

    @Test
    void isSelectable_supportedCliAgents_shouldBeTrue() {
        assertTrue(AgentType.CLAUDE.isSelectable());
        assertTrue(AgentType.CODEX.isSelectable());
    }

    @Test
    void isSelectable_native_shouldBeFalse() {
        // NATIVE 是进程内诊断引擎, 不作为用户/后台可选的 CLI agent 暴露
        assertFalse(AgentType.NATIVE.isSelectable());
    }

    @Test
    void parseSelectable_validCaseInsensitiveWithWhitespace_shouldReturnType() {
        assertEquals(AgentType.CLAUDE, AgentType.parseSelectable("CLAUDE"));
        assertEquals(AgentType.CODEX, AgentType.parseSelectable("codex"));
    }

    @Test
    void parseSelectable_nullOrBlank_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> AgentType.parseSelectable(null));
        assertThrows(IllegalArgumentException.class, () -> AgentType.parseSelectable("   "));
    }

    @Test
    void parseSelectable_unknown_shouldThrow() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AgentType.parseSelectable("gemini"));
        assertTrue(ex.getMessage().contains("gemini"));
    }

    @Test
    void parseSelectable_removedCursor_shouldThrow() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AgentType.parseSelectable("cursor"));
        assertTrue(ex.getMessage().contains("cursor"));
    }

    @Test
    void parseSelectable_nonSelectableNative_shouldThrow() {
        // 即使是合法枚举值, NATIVE 也不可被选择
        assertThrows(IllegalArgumentException.class, () -> AgentType.parseSelectable("NATIVE"));
    }

    @Test
    void resolveSelection_blankInput_shouldUseSelectableDefault() {
        assertEquals(AgentType.CODEX, AgentType.resolveSelection(null, AgentType.CODEX));
        assertEquals(AgentType.CLAUDE, AgentType.resolveSelection("   ", AgentType.CLAUDE));
    }

    @Test
    void resolveSelection_explicitInput_shouldOverrideDefault() {
        assertEquals(AgentType.CODEX, AgentType.resolveSelection(" codex ", AgentType.CLAUDE));
    }

    @Test
    void resolveSelection_invalidDefault_shouldFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentType.resolveSelection(null, AgentType.NATIVE));
        assertThrows(IllegalArgumentException.class,
                () -> AgentType.resolveSelection(null, null));
    }
}
