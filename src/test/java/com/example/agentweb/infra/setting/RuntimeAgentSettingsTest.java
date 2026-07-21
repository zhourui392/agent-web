package com.example.agentweb.infra.setting;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentDefaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeAgentSettings} 单测:DB 优先、缺失回退 yml 种子、写后刷新缓存、chat 版本自增。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
class RuntimeAgentSettingsTest {

    private AppSettingRepository repo;
    private AgentDefaultProperties chatDefaults;

    @BeforeEach
    void setUp() {
        repo = mock(AppSettingRepository.class);
        chatDefaults = new AgentDefaultProperties();
        chatDefaults.setDefaultType(AgentType.CLAUDE);
        // 默认 repo 全空
        when(repo.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    }

    private RuntimeAgentSettings newSettings() {
        return new RuntimeAgentSettings(repo, chatDefaults);
    }

    @Test
    void getters_dbEmpty_shouldFallbackToYmlSeeds() {
        RuntimeAgentSettings s = newSettings();
        assertEquals(AgentType.CLAUDE, s.getChatDefaultAgent());
        assertEquals(0L, s.getChatDefaultAgentVersion());
    }

    @Test
    void getChatDefaultAgent_supportedDbValue_shouldWinOverSeed() {
        when(repo.get(RuntimeAgentSettings.KEY_CHAT_AGENT)).thenReturn(Optional.of("CODEX"));
        assertEquals(AgentType.CODEX, newSettings().getChatDefaultAgent());
    }

    @Test
    void getChatDefaultAgent_removedCursor_shouldFallbackToSeed() {
        when(repo.get(RuntimeAgentSettings.KEY_CHAT_AGENT)).thenReturn(Optional.of("CURSOR"));
        assertEquals(AgentType.CLAUDE, newSettings().getChatDefaultAgent());
    }

    @Test
    void getChatDefaultAgent_dbGarbage_shouldFallbackToSeed() {
        when(repo.get(RuntimeAgentSettings.KEY_CHAT_AGENT)).thenReturn(Optional.of("gemini"));
        assertEquals(AgentType.CLAUDE, newSettings().getChatDefaultAgent());
    }

    @Test
    void setChatDefaultAgent_shouldPersistAndBumpVersion() {
        when(repo.get(RuntimeAgentSettings.KEY_CHAT_AGENT_VERSION)).thenReturn(Optional.of("3"));
        RuntimeAgentSettings s = newSettings();
        s.setChatDefaultAgent(AgentType.CODEX);

        assertEquals(AgentType.CODEX, s.getChatDefaultAgent());
        assertEquals(4L, s.getChatDefaultAgentVersion());
        verify(repo).put(eq(RuntimeAgentSettings.KEY_CHAT_AGENT), eq("CODEX"), anyLong());
        verify(repo).put(eq(RuntimeAgentSettings.KEY_CHAT_AGENT_VERSION), eq("4"), anyLong());
    }
}
