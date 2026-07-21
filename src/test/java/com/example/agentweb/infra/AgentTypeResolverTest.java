package com.example.agentweb.infra;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.setting.RuntimeAgentSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 类型解析器单元测试。
 * <p>覆盖 null/空/合法/未知/大小写不敏感等边界, 缺失时回退运行时默认 {@link RuntimeAgentSettings}。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
class AgentTypeResolverTest {

    @Test
    void resolve_nullInput_shouldReturnRuntimeDefault() {
        AgentTypeResolver resolver = resolverWithDefault(AgentType.CODEX);
        assertEquals(AgentType.CODEX, resolver.resolve(null));
    }

    @Test
    void resolve_emptyInput_shouldReturnRuntimeDefault() {
        AgentTypeResolver resolver = resolverWithDefault(AgentType.CLAUDE);
        assertEquals(AgentType.CLAUDE, resolver.resolve(""));
        assertEquals(AgentType.CLAUDE, resolver.resolve("   "));
    }

    @Test
    void resolve_validUppercase_shouldReturnMatchingType() {
        AgentTypeResolver resolver = resolverWithDefault(AgentType.CLAUDE);
        assertEquals(AgentType.CODEX, resolver.resolve("CODEX"));
        assertEquals(AgentType.CLAUDE, resolver.resolve("CLAUDE"));
    }

    @Test
    void resolve_validLowercaseOrMixedCase_shouldNormalizeCaseInsensitive() {
        AgentTypeResolver resolver = resolverWithDefault(AgentType.CLAUDE);
        assertEquals(AgentType.CODEX, resolver.resolve("codex"));
        assertEquals(AgentType.CODEX, resolver.resolve("Codex"));
        assertEquals(AgentType.CLAUDE, resolver.resolve("ClAuDe"));
    }

    @Test
    void resolve_inputWithSurroundingWhitespace_shouldTrim() {
        AgentTypeResolver resolver = resolverWithDefault(AgentType.CLAUDE);
        assertEquals(AgentType.CODEX, resolver.resolve("  codex  "));
    }

    @Test
    void resolve_unknownType_shouldThrowIllegalArgument() {
        AgentTypeResolver resolver = resolverWithDefault(AgentType.CLAUDE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("gemini"));
        assertEquals(true, ex.getMessage().contains("gemini"));
    }

    @Test
    void resolve_removedCursor_shouldThrowIllegalArgument() {
        AgentTypeResolver resolver = resolverWithDefault(AgentType.CLAUDE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("cursor"));
        assertEquals(true, ex.getMessage().contains("cursor"));
    }

    private AgentTypeResolver resolverWithDefault(AgentType defaultType) {
        RuntimeAgentSettings settings = mock(RuntimeAgentSettings.class);
        when(settings.getChatDefaultAgent()).thenReturn(defaultType);
        return new AgentTypeResolver(settings);
    }
}
