package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatAppServiceImpl} 分享编排单测：token 生成委托领域规则、
 * 会话不存在拒绝、续聊透传会话持久态 resumeId/env。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public class ChatAppServiceImplShareTest {

    private SessionRepository sessionRepository;
    private ChatAppServiceImpl service;

    @BeforeEach
    public void setUp() {
        sessionRepository = mock(SessionRepository.class);
        SessionCache sessionCache = mock(SessionCache.class);
        AgentGateway gateway = mock(AgentGateway.class);
        Executor agentExecutor = mock(Executor.class);
        SlashCommandExpander commandExpander = mock(SlashCommandExpander.class);
        StreamOutputExtractor outputExtractor = mock(StreamOutputExtractor.class);
        AgentTypeResolver agentTypeResolver = mock(AgentTypeResolver.class);
        UploadPicStore uploadPicStore = mock(UploadPicStore.class);
        UploadFileStore uploadFileStore = mock(UploadFileStore.class);
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, gateway, agentExecutor,
                commandExpander, outputExtractor, agentTypeResolver, uploadPicStore, uploadFileStore,
                java.util.Optional.empty(),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> java.util.Optional.empty()));
    }

    private ChatSession session(String id) {
        return new ChatSession(id, AgentType.CLAUDE, "/tmp/wd", Instant.now(), new ArrayList<>());
    }

    @Test
    public void shareSession_should_persist_domain_generated_token_and_return_actual() {
        when(sessionRepository.findById("sess-1")).thenReturn(session("sess-1"));
        // 已存在的 token 优先返回(setShareToken 写库 upsert,返回最终持久态 token)
        when(sessionRepository.setShareToken(eq("sess-1"), anyString())).thenReturn("existing-token-abc");

        String token = service.shareSession("sess-1");

        assertEquals("existing-token-abc", token);
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionRepository, times(1)).setShareToken(eq("sess-1"), tokenCaptor.capture());
        assertNotNull(tokenCaptor.getValue());
        assertEquals(16, tokenCaptor.getValue().length(), "候选 token 应为领域规则的 16 位");
    }

    @Test
    public void shareSession_should_reject_unknown_session_without_persisting() {
        when(sessionRepository.findById("ghost")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.shareSession("ghost"));
        verify(sessionRepository, never()).setShareToken(anyString(), anyString());
    }

    @Test
    public void streamSharedMessage_should_reject_unknown_token() {
        when(sessionRepository.findByShareToken("ghost")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.streamSharedMessage("ghost", "hi", true));
    }

    @Test
    public void streamSharedMessage_should_pass_session_persistent_resumeId_and_env() {
        ChatSession session = session("sess-9");
        session.setResumeId("resume-77");
        session.setEnv("prod");
        when(sessionRepository.findByShareToken("tok9")).thenReturn(session);
        ChatAppServiceImpl spied = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter())
                .when(spied).streamMessage(anyString(), anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());

        spied.streamSharedMessage("tok9", "hi there", true);

        verify(spied, times(1)).streamMessage("sess-9", "hi there", "resume-77", "prod", true);
    }
}
