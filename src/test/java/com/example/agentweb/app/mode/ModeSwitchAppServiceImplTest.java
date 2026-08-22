package com.example.agentweb.app.mode;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.mode.ChatMode;
import com.example.agentweb.domain.mode.ChatModeRepository;
import com.example.agentweb.domain.mode.DuplicateHandoffException;
import com.example.agentweb.domain.mode.HandoffDocument;
import com.example.agentweb.domain.mode.HandoffDocumentRepository;
import com.example.agentweb.config.ModeSwitchProperties;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模式切换用例编排回归：活跃 run 拒绝、幂等回放、事务补偿、归属校验。
 *
 * @author alex
 * @since 2026-08-20
 */
class ModeSwitchAppServiceImplTest {

    private SessionRepository sessionRepository;
    private SessionCache sessionCache;
    private ChatModeRepository modeRepository;
    private HandoffDocumentRepository handoffRepository;
    private ChatRunActivityGuard activityGuard;
    private HandoffTranscriptQueryService transcriptQueryService;
    private HandoffFilePort handoffFilePort;
    private ModeSwitchAppService service;
    private TransactionTemplate transactionTemplate;

    private ChatSession sourceSession;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        sessionCache = mock(SessionCache.class);
        modeRepository = mock(ChatModeRepository.class);
        handoffRepository = mock(HandoffDocumentRepository.class);
        activityGuard = mock(ChatRunActivityGuard.class);
        transcriptQueryService = mock(HandoffTranscriptQueryService.class);
        handoffFilePort = mock(HandoffFilePort.class);
        // 真实事务模板不可用（无 DataSource），以直通执行替代：事务边界语义由仓储原子性兜底
        transactionTemplate = new TransactionTemplate() {
            @Override
            public void executeWithoutResult(
                    java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                action.accept(null);
            }
        };
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(null) {
            @Override
            public String currentUserId() {
                return "u1";
            }
        };
        ModeSwitchProperties properties = new ModeSwitchProperties();
        properties.setTranscriptTail(5);
        service = new ModeSwitchAppServiceImpl(sessionRepository, sessionCache,
                modeRepository, handoffRepository, activityGuard,
                transcriptQueryService, handoffFilePort, currentUserProvider,
                properties, transactionTemplate);
        sourceSession = new ChatSession("s1", AgentType.CLAUDE, "/tmp/wd",
                Instant.now(), new ArrayList<>());
        sourceSession.setUserId("u1");
        when(sessionRepository.findById("s1")).thenReturn(sourceSession);
        when(transcriptQueryService.loadTranscript(eq("s1"), eq(5)))
                .thenReturn(new HandoffTranscriptFacts("首轮目标",
                        java.util.List.of(new HandoffTranscriptFacts.TranscriptMessage(
                                "user", "你好")),
                        java.util.List.of(new HandoffTranscriptFacts.TranscriptFileChange(
                                "src/A.java", "MODIFIED"))));
        when(handoffFilePort.export(anyString(), anyString(), anyString()))
                .thenReturn(".workbench/handoff/h1.md");
    }

    @Test
    void rejectsWhenActiveRunExists() {
        doThrow(new ActiveChatRunExistsException("s1")).when(activityGuard)
                .requireInactive("s1");
        assertThrows(ActiveChatRunExistsException.class, () ->
                service.switchMode("s1", null, "idem-1"));
        verify(handoffFilePort, never()).export(anyString(), anyString(), anyString());
    }

    @Test
    void replaysExistingResultForSameIdempotencyKey() {
        HandoffDocument existing = new HandoffDocument("h0", "s1", "s-old", null, null,
                ".workbench/handoff/h0.md", "idem-1", Instant.now());
        when(handoffRepository.findByFromSessionAndIdempotencyKey("s1", "idem-1"))
                .thenReturn(Optional.of(existing));
        ModeSwitchAppService.ModeSwitchResult result =
                service.switchMode("s1", null, "idem-1");
        assertEquals("s-old", result.newSessionId());
        assertEquals("h0", result.handoffDocumentId());
        verify(handoffFilePort, never()).export(anyString(), anyString(), anyString());
    }

    @Test
    void switchToDefaultModeCreatesHandoffAndSwitchedSession() {
        ModeSwitchAppService.ModeSwitchResult result =
                service.switchMode("s1", null, "idem-1");
        // handoff id 为服务端生成 UUID，文件名与记录 id 一致
        assertEquals(36, result.handoffDocumentId().length());
        ArgumentCaptor<ChatSession> sessionCaptor =
                ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).saveSession(sessionCaptor.capture());
        ChatSession target = sessionCaptor.getValue();
        assertEquals("s1", target.getSwitchedFromSessionId());
        assertEquals(result.handoffDocumentId(), target.getHandoffDocumentId());
        assertNull(target.getModeId());
        assertEquals("/tmp/wd", target.getWorkingDir());
        assertEquals("u1", target.getUserId());
        verify(sessionCache).save(target);
        ArgumentCaptor<HandoffDocument> handoffCaptor =
                ArgumentCaptor.forClass(HandoffDocument.class);
        verify(handoffRepository).save(handoffCaptor.capture());
        assertEquals("s1", handoffCaptor.getValue().getFromSessionId());
        assertEquals(target.getId(), handoffCaptor.getValue().getToSessionId());
        assertEquals("idem-1", handoffCaptor.getValue().getIdempotencyKey());
        // 交接文件在事务前写入，内容含首轮目标与文件清单
        verify(handoffFilePort).export(eq("/tmp/wd"), anyString(), anyString());
    }

    @Test
    void transactionFailureTriggersCompensatingFileDelete() {
        doThrow(new IllegalStateException("db down"))
                .when(handoffRepository).save(any(HandoffDocument.class));
        assertThrows(IllegalStateException.class, () ->
                service.switchMode("s1", null, "idem-1"));
        verify(handoffFilePort).deleteQuietly(eq("/tmp/wd"),
                eq(".workbench/handoff/h1.md"));
    }

    @Test
    void concurrentDuplicateHandoffReplaysExistingResult() {
        HandoffDocument raced = new HandoffDocument("h9", "s1", "s-raced", null, null,
                ".workbench/handoff/h9.md", "idem-1", Instant.now());
        AtomicReference<HandoffDocument> replayRef = new AtomicReference<>();
        doAnswer(invocation -> {
            HandoffDocument saving = invocation.getArgument(0);
            if (replayRef.get() == null) {
                replayRef.set(raced);
                // 模拟唯一索引兜底：首次保存撞冲突，回读已有记录
                throw new DuplicateHandoffException("duplicate");
            }
            return saving;
        }).when(handoffRepository).save(any(HandoffDocument.class));
        when(handoffRepository.findByFromSessionAndIdempotencyKey("s1", "idem-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raced));
        ModeSwitchAppService.ModeSwitchResult result =
                service.switchMode("s1", null, "idem-1");
        assertEquals("s-raced", result.newSessionId());
        assertEquals("h9", result.handoffDocumentId());
    }

    @Test
    void rejectsForeignSourceSession() {
        sourceSession.setUserId("someone-else");
        assertThrows(com.example.agentweb.domain.chat.SessionDeletionForbiddenException.class,
                () -> service.switchMode("s1", null, "idem-1"));
        verify(handoffFilePort, never()).export(anyString(), anyString(), anyString());
    }

    @Test
    void targetModeMustBeOwnedByCurrentUser() {
        ChatMode foreign = ChatMode.create("m2", "u2", "reviewer", "评审",
                null, null, null, null, null, null,
                com.example.agentweb.domain.mode.ModeCapabilities.empty(), Instant.now());
        when(modeRepository.find("m2")).thenReturn(Optional.of(foreign));
        assertThrows(com.example.agentweb.domain.mode.ModeNotFoundException.class,
                () -> service.switchMode("s1", "m2", "idem-1"));
        verify(handoffFilePort, never()).export(anyString(), anyString(), anyString());
    }

    @Test
    void transcriptContainsGoalTailAndFiles() {
        org.mockito.ArgumentCaptor<String> contentCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        service.switchMode("s1", null, "idem-1");
        verify(handoffFilePort).export(anyString(), anyString(), contentCaptor.capture());
        String content = contentCaptor.getValue();
        assertTrue(content.contains("首轮目标"));
        assertTrue(content.contains("src/A.java"));
        assertTrue(content.contains("MODIFIED"));
        assertTrue(content.contains("会话交接"));
    }

    @Test
    void switchPersistsSystemNoteIntoNewSessionConversation() {
        ChatMode target = ChatMode.create("m2", "u1", "reviewer", "评审模式",
                null, null, null, null, null, null,
                com.example.agentweb.domain.mode.ModeCapabilities.empty(), Instant.now());
        when(modeRepository.find("m2")).thenReturn(Optional.of(target));

        ModeSwitchAppService.ModeSwitchResult result = service.switchMode("s1", "m2", "idem-1");

        ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<com.example.agentweb.domain.chat.ChatMessage> messageCaptor =
                ArgumentCaptor.forClass(com.example.agentweb.domain.chat.ChatMessage.class);
        verify(sessionRepository).addMessage(sessionIdCaptor.capture(), messageCaptor.capture());
        assertEquals(result.newSessionId(), sessionIdCaptor.getValue());
        assertEquals("system", messageCaptor.getValue().getRole());
        String note = messageCaptor.getValue().getContent();
        // 源会话未绑定模式记为默认模式；目标模式名与交接说明必须可见
        assertTrue(note.contains("默认模式"));
        assertTrue(note.contains("评审模式"));
        assertTrue(note.contains(result.handoffDocumentId()));
    }
}
