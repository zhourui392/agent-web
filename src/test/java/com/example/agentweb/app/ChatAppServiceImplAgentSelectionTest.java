package com.example.agentweb.app;

import com.example.agentweb.app.agentrun.AgentCatalogService;
import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.domain.agentrun.AgentPolicyViolationException;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSession 创建时的 AgentCatalog 编排测试。
 *
 * @author alex
 * @since 2026-07-29
 */
class ChatAppServiceImplAgentSelectionTest {

    private SessionCache sessionCache;
    private SessionRepository sessionRepository;
    private AgentGateway gateway;
    private ChatAgentDefaults defaults;
    private AgentCatalogService catalog;
    private ChatAppServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionCache = mock(SessionCache.class);
        sessionRepository = mock(SessionRepository.class);
        gateway = mock(AgentGateway.class);
        defaults = mock(ChatAgentDefaults.class);
        catalog = mock(AgentCatalogService.class);
        service = new ChatAppServiceImpl(sessionCache, sessionRepository,
                gateway, mock(SlashCommandExpander.class), defaults,
                mock(UploadPicStorage.class), mock(UploadFileStorage.class),
                Optional.<RecallObservationRecorder>empty(), mock(CurrentUserProvider.class),
                catalog);
        when(defaults.getChatDefaultAgent()).thenReturn(AgentType.CODEX);
    }

    @Test
    void startSession_nativeSelection_shouldDelegateEnvironmentAwareCatalogGuard() {
        when(catalog.resolveChatSelection("NATIVE", AgentType.CODEX, "test"))
                .thenReturn(AgentType.NATIVE);

        ChatSession session = service.startSession(
                new StartSessionCommand("NATIVE", "/tmp/work", "test"), "127.0.0.1");

        assertEquals(AgentType.NATIVE, session.getAgentType());
        assertEquals("test", session.getEnv());
        verify(sessionCache).save(session);
        verify(sessionRepository).saveSession(session);
    }

    @Test
    void startSession_catalogRejectsSelection_shouldNotPersistAnything() {
        RuntimeException failure = new IllegalStateException("native unavailable");
        when(catalog.resolveChatSelection("NATIVE", AgentType.CODEX, "test"))
                .thenThrow(failure);

        assertThrows(IllegalStateException.class, () -> service.startSession(
                new StartSessionCommand("NATIVE", "/tmp/work", "test"), "127.0.0.1"));

        verify(sessionCache, never()).save(org.mockito.ArgumentMatchers.any(ChatSession.class));
        verify(sessionRepository, never()).saveSession(
                org.mockito.ArgumentMatchers.any(ChatSession.class));
    }

    @Test
    void legacySend_nativeShouldRejectUnsupportedOneShotBeforePersistingUserMessage()
            throws Exception {
        ChatSession session = new ChatSession(AgentType.NATIVE, "/tmp/work");
        when(sessionCache.find(session.getId())).thenReturn(session);
        doThrow(new AgentPolicyViolationException(
                "AGENT_SURFACE_UNAVAILABLE", "NATIVE requires ChatRun streaming"))
                .when(gateway).requireOneShotSupported(AgentType.NATIVE);

        AgentPolicyViolationException error = assertThrows(AgentPolicyViolationException.class,
                () -> service.sendMessage(session.getId(), new SendMessageCommand("symptom")));

        assertEquals("AGENT_SURFACE_UNAVAILABLE", error.getCode());
        verify(sessionRepository, never()).addMessage(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(com.example.agentweb.domain.chat.ChatMessage.class));
        verify(gateway, never()).runOnce(
                org.mockito.ArgumentMatchers.any(AgentType.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
