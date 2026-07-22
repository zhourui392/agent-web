package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionDeletionForbiddenException;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;


import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public class ChatAppServiceImplDeleteSessionTest {

    private SessionCache sessionCache;
    private SessionRepository sessionRepository;
    private UploadPicStore uploadPicStore;
    private UploadFileStore uploadFileStore;
    private ChatRunActivityGuard chatRunActivityGuard;
    private ChatAppServiceImpl service;

    @BeforeEach
    public void setUp() {
        sessionCache = mock(SessionCache.class);
        sessionRepository = mock(SessionRepository.class);
        uploadPicStore = mock(UploadPicStore.class);
        uploadFileStore = mock(UploadFileStore.class);
        chatRunActivityGuard = mock(ChatRunActivityGuard.class);

        AgentGateway gateway = mock(AgentGateway.class);
        SlashCommandExpander commandExpander = mock(SlashCommandExpander.class);
        AgentTypeResolver agentTypeResolver = mock(AgentTypeResolver.class);

        service = new ChatAppServiceImpl(sessionCache, sessionRepository, gateway,
                commandExpander, agentTypeResolver, uploadPicStore, uploadFileStore,
                java.util.Optional.empty(),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> java.util.Optional.empty()));
        service.configureChatRunActivityGuard(chatRunActivityGuard);
    }

    @Test
    public void deleteSession_existing_session_clears_images_files_cache_and_deletes_persisted() {
        String sessionId = "sess-1";
        String workingDir = "/tmp/wd";
        ChatSession session = new ChatSession(sessionId, AgentType.CLAUDE, workingDir,
                java.time.Instant.now(), new java.util.ArrayList<>());
        when(sessionRepository.findById(sessionId)).thenReturn(session);

        service.deleteSession(sessionId);

        // 顺序:先删图与附件(用得到 workingDir),再清缓存,最后删持久化
        InOrder inOrder = inOrder(uploadPicStore, uploadFileStore, sessionCache, sessionRepository);
        inOrder.verify(uploadPicStore).deleteSessionImages(workingDir, sessionId);
        inOrder.verify(uploadFileStore).deleteSessionFiles(workingDir, sessionId);
        inOrder.verify(sessionCache).remove(sessionId);
        inOrder.verify(sessionRepository).deleteById(sessionId);
    }

    @Test
    public void deleteSession_session_not_found_should_skip_image_and_file_deletion_still_clears_cache_and_persisted() {
        // 历史脏数据/重复调用场景:repo 查不到 session,仍要保证幂等清理
        String sessionId = "sess-missing";
        when(sessionRepository.findById(sessionId)).thenReturn(null);

        service.deleteSession(sessionId);

        verify(uploadPicStore, never()).deleteSessionImages(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(uploadFileStore, never()).deleteSessionFiles(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(sessionCache).remove(sessionId);
        verify(sessionRepository).deleteById(sessionId);
        verify(chatRunActivityGuard, never()).requireInactive(anyString());
    }

    @Test
    public void deleteSession_non_owner_should_be_rejected_and_touch_no_data() {
        // 当前用户为空(setUp 的 CurrentUserProvider 无上下文); 会话归属 alice → 删他人会话必须被拒
        String sessionId = "sess-owned";
        ChatSession session = new ChatSession(sessionId, AgentType.CLAUDE, "/tmp/wd",
                java.time.Instant.now(), new java.util.ArrayList<>());
        session.setUserId("alice");
        when(sessionRepository.findById(sessionId)).thenReturn(session);

        assertThrows(SessionDeletionForbiddenException.class, () -> service.deleteSession(sessionId));

        // 拒绝时不得清图/清附件/清缓存/删库
        verify(uploadPicStore, never()).deleteSessionImages(anyString(), anyString());
        verify(uploadFileStore, never()).deleteSessionFiles(anyString(), anyString());
        verify(sessionCache, never()).remove(anyString());
        verify(sessionRepository, never()).deleteById(anyString());
        verify(chatRunActivityGuard, never()).requireInactive(anyString());
    }

    @Test
    public void deleteSession_activeRun_should_be_rejected_after_owner_check() {
        ChatSession session = new ChatSession("sess-active", AgentType.CLAUDE, "/tmp/wd",
                java.time.Instant.now(), new java.util.ArrayList<>());
        when(sessionRepository.findById("sess-active")).thenReturn(session);
        doThrow(new ActiveChatRunExistsException("sess-active"))
                .when(chatRunActivityGuard).requireInactive("sess-active");

        assertThrows(ActiveChatRunExistsException.class,
                () -> service.deleteSession("sess-active"));

        org.mockito.InOrder order = inOrder(sessionRepository, chatRunActivityGuard);
        order.verify(sessionRepository).findById("sess-active");
        order.verify(chatRunActivityGuard).requireInactive("sess-active");
        verify(uploadPicStore, never()).deleteSessionImages(anyString(), anyString());
        verify(uploadFileStore, never()).deleteSessionFiles(anyString(), anyString());
        verify(sessionCache, never()).remove(anyString());
        verify(sessionRepository, never()).deleteById(anyString());
    }
}
