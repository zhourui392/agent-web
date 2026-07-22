package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App 单测: {@link ChatAppServiceImpl#getSession} 缓存命中路径的会话归属闸门。
 *
 * <p>进程级 {@link SessionCache} 不做 user_id 过滤, 缓存命中会绕过 {@link SessionRepository}
 * 读侧的 SQL 隔离; 本测试钉死缓存命中也按当前用户归属判定: 非属主返回 null(不可见即不存在),
 * 与 repo 过滤同语义, 堵住 send / listCommands 的越权读。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class ChatAppServiceImplOwnershipTest {

    private SessionCache sessionCache;
    private SessionRepository sessionRepository;
    private CurrentUserProvider currentUserProvider;
    private ChatAppServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionCache = mock(SessionCache.class);
        sessionRepository = mock(SessionRepository.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, mock(AgentGateway.class),
                mock(SlashCommandExpander.class), mock(AgentTypeResolver.class),
                mock(UploadPicStore.class), mock(UploadFileStore.class), Optional.empty(), currentUserProvider);
    }

    private ChatSession ownedBy(String owner) {
        ChatSession s = new ChatSession("s1", AgentType.CLAUDE, "/tmp/wd",
                Instant.parse("2026-06-07T00:00:00Z"), Collections.emptyList());
        s.setUserId(owner);
        return s;
    }

    @Test
    @DisplayName("缓存命中 + 当前用户是属主 → 返回会话, 不回落 DB")
    void cacheHit_owner_returnsSession() {
        ChatSession s = ownedBy("userA");
        when(sessionCache.find("s1")).thenReturn(s);
        when(currentUserProvider.shouldFilter()).thenReturn(true);
        when(currentUserProvider.currentUserId()).thenReturn("userA");

        assertSame(s, service.getSession("s1"));
        verify(sessionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("缓存命中 + 当前用户非属主(普通用户) → 返回 null, 堵住缓存绕过")
    void cacheHit_otherUser_returnsNull() {
        ChatSession s = ownedBy("userA");
        when(sessionCache.find("s1")).thenReturn(s);
        when(currentUserProvider.shouldFilter()).thenReturn(true);
        when(currentUserProvider.currentUserId()).thenReturn("userB");

        assertNull(service.getSession("s1"), "非属主缓存命中应视为不存在");
    }

    @Test
    @DisplayName("缓存命中 + 老数据(owner 为 null) → 公共池放行")
    void cacheHit_legacyNullOwner_returnsSession() {
        ChatSession s = ownedBy(null);
        when(sessionCache.find("s1")).thenReturn(s);
        when(currentUserProvider.shouldFilter()).thenReturn(true);
        when(currentUserProvider.currentUserId()).thenReturn("userB");

        assertSame(s, service.getSession("s1"));
    }

    @Test
    @DisplayName("缓存命中 + admin / 后台无上下文(shouldFilter=false) → bypass 看全部")
    void cacheHit_noFilter_returnsSession() {
        ChatSession s = ownedBy("userA");
        when(sessionCache.find("s1")).thenReturn(s);
        when(currentUserProvider.shouldFilter()).thenReturn(false);

        assertSame(s, service.getSession("s1"));
    }
}
