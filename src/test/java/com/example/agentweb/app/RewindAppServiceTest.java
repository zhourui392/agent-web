package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import com.example.agentweb.interfaces.dto.TruncateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App 层 Mockito 单测：覆盖 rewind 相关的 ChatAppServiceImpl 编排逻辑。
 *
 * <p>从原 {@code RewindFeatureTest}（@SpringBootTest）下沉来。Interface 切片只关心 HTTP 透传，
 * 这里关心：</p>
 * <ul>
 *   <li>{@code truncateFrom} 起点为 user 消息时 prefillContent 取其 content</li>
 *   <li>{@code truncateFrom} 起点为 assistant 时 prefillContent 为空</li>
 *   <li>{@code streamMessage} 在 DB 端 messages 非空 + resumeId 为空时注入 history prefix</li>
 *   <li>{@code streamMessage} 在 DB 端 resumeId 非空时不注入</li>
 *   <li>{@code streamMessage} 首次发消息（messages 为空）时不注入</li>
 * </ul>
 *
 * <p>持久化层细节（消息 id 自增、DB truncate 行为）见 {@code SqliteSessionRepoTest}。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-25
 */
public class RewindAppServiceTest {

    private SessionCache sessionCache;
    private SessionRepository sessionRepository;
    private AgentGateway gateway;
    private SlashCommandExpander commandExpander;
    private ChatAppServiceImpl service;

    @BeforeEach
    public void setUp() {
        sessionCache = mock(SessionCache.class);
        sessionRepository = mock(SessionRepository.class);
        gateway = mock(AgentGateway.class);
        commandExpander = mock(SlashCommandExpander.class);
        StreamOutputExtractor outputExtractor = mock(StreamOutputExtractor.class);
        AgentTypeResolver agentTypeResolver = mock(AgentTypeResolver.class);
        UploadPicStore uploadPicStore = mock(UploadPicStore.class);
        UploadFileStore uploadFileStore = mock(UploadFileStore.class);
        // 同步执行器: streamMessage 内 agentExecutor.execute(...) 直接在当前线程跑, 免 timeout 等待。
        Executor agentExecutor = Runnable::run;
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, gateway, agentExecutor,
                commandExpander, outputExtractor, agentTypeResolver, uploadPicStore, uploadFileStore,
                java.util.Optional.empty(),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> java.util.Optional.empty()));

        // commandExpander 默认透传: 不是 slash command 就原样返回。
        when(commandExpander.expandIfCommand(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        // assistant 消息在 buildHistoryPrefix 中走 extractPlainText 剥离 stream-json,
        // mock 默认返回 null 会被视为空文本过滤掉, 这里改为原样返回。
        when(outputExtractor.extractPlainText(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** 构造一个含给定 messages + resumeId 的 ChatSession 快照, 用于 sessionRepository.findById 返回。 */
    private ChatSession sessionWith(String id, String resumeId, List<ChatMessage> messages) {
        ChatSession s = new ChatSession(id, AgentType.CLAUDE, "/tmp", Instant.now(), messages);
        if (resumeId != null) {
            s.setResumeId(resumeId);
        }
        return s;
    }

    private static ChatMessage msg(long id, String role, String content) {
        return new ChatMessage(id, role, content, Instant.now());
    }

    // ── truncateFrom 编排 ──

    @Test
    public void appService_truncateFrom_should_return_prefill_for_user_message() {
        String sessionId = "s1";
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(msg(1L, "user", "first question"));
        msgs.add(msg(2L, "assistant", "first answer"));
        msgs.add(msg(3L, "user", "this is the rewind point"));
        msgs.add(msg(4L, "assistant", "off-track answer"));
        when(sessionRepository.findById(sessionId)).thenReturn(sessionWith(sessionId, "cli-resume", msgs));
        when(sessionRepository.truncateFrom(sessionId, 3L)).thenReturn(2);

        TruncateResult result = service.truncateFrom(sessionId, 3L);

        assertEquals(2, result.getDeletedCount());
        assertEquals("this is the rewind point", result.getPrefillContent(),
                "起点是 user 消息, prefillContent 应为其原文");
        assertTrue(result.isResumeIdCleared(), "原本有 resumeId, 应标记已清除");
        // Repository 端 truncateFrom 被真实调用, 缓存被失效——保证下次读重走 DB
        verify(sessionRepository).truncateFrom(sessionId, 3L);
        verify(sessionCache).remove(sessionId);
    }

    @Test
    public void appService_truncateFrom_assistant_target_should_return_empty_prefill() {
        String sessionId = "s2";
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(msg(1L, "user", "q"));
        msgs.add(msg(2L, "assistant", "a"));
        // resumeId 为 null 模拟"从未设置过"的会话
        when(sessionRepository.findById(sessionId)).thenReturn(sessionWith(sessionId, null, msgs));
        when(sessionRepository.truncateFrom(sessionId, 2L)).thenReturn(1);

        TruncateResult result = service.truncateFrom(sessionId, 2L);

        assertEquals("", result.getPrefillContent(), "起点是 assistant, prefillContent 应为空");
        assertFalse(result.isResumeIdCleared(), "原本就无 resumeId");
    }

    // ── streamMessage history-prefix 注入 ──

    @Test
    public void streamMessage_should_inject_history_prefix_after_truncate() throws Exception {
        // 构造"截断后"状态: messages 非空 + resume_id 为空
        String sessionId = "s-truncated";
        List<ChatMessage> seeded = new ArrayList<>();
        seeded.add(msg(10L, "user", "alpha"));
        seeded.add(msg(11L, "assistant", "beta"));
        ChatSession fresh = sessionWith(sessionId, null, seeded);
        // getSession 走 cache miss → findById 路径, 接着 resolveHistoryForPrefix 也走 findById
        when(sessionCache.find(sessionId)).thenReturn(null);
        when(sessionRepository.findById(sessionId)).thenReturn(fresh);

        // Gateway mock: 立即触发 onExit 让 SseEmitter 完成, 同时把 runStream 调用记录下来。
        doAnswer(inv -> {
            IntConsumer onExit = inv.getArgument(8);
            onExit.accept(0);
            return null;
        }).when(gateway).runStream(any(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        service.streamMessage(sessionId, "the new question", null, null, true);

        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), msgCap.capture(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        String sentToCli = msgCap.getValue();
        assertTrue(sentToCli.contains("<conversation_history>"),
                "截断后重开应注入 <conversation_history> 前缀, 实际: " + sentToCli);
        assertTrue(sentToCli.contains("[user]: alpha"), "历史 user 消息应被包含");
        assertTrue(sentToCli.contains("[assistant]: beta"), "历史 assistant 消息应被包含");
        assertTrue(sentToCli.contains("<new_user_message>"), "应有 <new_user_message> 标记");
        assertTrue(sentToCli.contains("the new question"), "当前 user 消息应在新消息块内");

        // 持久化的 user message 仍为原文, 不带前缀
        ArgumentCaptor<ChatMessage> persistedMsg = ArgumentCaptor.forClass(ChatMessage.class);
        verify(sessionRepository).addMessageReturningId(eq(sessionId), persistedMsg.capture());
        ChatMessage saved = persistedMsg.getValue();
        assertEquals("user", saved.getRole());
        assertEquals("the new question", saved.getContent(),
                "持久化的 user message 必须是原文, 而非带前缀的扩展版");
    }

    @Test
    public void streamMessage_should_ignore_stale_request_resumeId_when_db_resumeId_cleared() throws Exception {
        String sessionId = "s-stale-client-resume";
        List<ChatMessage> seeded = new ArrayList<>();
        seeded.add(msg(10L, "user", "alpha"));
        seeded.add(msg(11L, "assistant", "beta"));
        ChatSession fresh = sessionWith(sessionId, null, seeded);
        when(sessionCache.find(sessionId)).thenReturn(null);
        when(sessionRepository.findById(sessionId)).thenReturn(fresh);

        doAnswer(inv -> {
            IntConsumer onExit = inv.getArgument(8);
            onExit.accept(0);
            return null;
        }).when(gateway).runStream(any(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        service.streamMessage(sessionId, "the new question", "old-broken-resume", null, true);

        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resumeCap = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), msgCap.capture(),
                anyString(), resumeCap.capture(), any(), anyLong(), any(), any(), any());

        assertTrue(msgCap.getValue().contains("<conversation_history>"),
                "DB resume_id 已清空时应忽略客户端旧 resumeId 并注入历史前缀");
        assertNull(resumeCap.getValue(), "传给 CLI 的 resumeId 必须为 null,避免继续坏线程");
    }

    @Test
    public void streamMessage_should_NOT_inject_prefix_when_resumeId_present() throws Exception {
        String sessionId = "s-resume";
        List<ChatMessage> seeded = new ArrayList<>();
        seeded.add(msg(1L, "user", "earlier"));
        ChatSession fresh = sessionWith(sessionId, "cli-existing", seeded);
        when(sessionCache.find(sessionId)).thenReturn(null);
        when(sessionRepository.findById(sessionId)).thenReturn(fresh);

        doAnswer(inv -> {
            IntConsumer onExit = inv.getArgument(8);
            onExit.accept(0);
            return null;
        }).when(gateway).runStream(any(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        service.streamMessage(sessionId, "follow-up", null, null, true);

        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), msgCap.capture(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        assertFalse(msgCap.getValue().contains("<conversation_history>"),
                "DB 端已有 resumeId, 不应触发前缀注入, 实际: " + msgCap.getValue());
    }

    @Test
    public void streamMessage_should_use_db_resumeId_when_request_resumeId_missing() throws Exception {
        String sessionId = "s-db-resume";
        List<ChatMessage> seeded = new ArrayList<>();
        seeded.add(msg(1L, "user", "earlier"));
        ChatSession fresh = sessionWith(sessionId, "cli-existing", seeded);
        when(sessionCache.find(sessionId)).thenReturn(null);
        when(sessionRepository.findById(sessionId)).thenReturn(fresh);

        doAnswer(inv -> {
            IntConsumer onExit = inv.getArgument(8);
            onExit.accept(0);
            return null;
        }).when(gateway).runStream(any(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        service.streamMessage(sessionId, "follow-up", null, null, true);

        ArgumentCaptor<String> resumeCap = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), anyString(),
                anyString(), resumeCap.capture(), any(), anyLong(), any(), any(), any());

        assertEquals("cli-existing", resumeCap.getValue(), "正常续聊应使用 DB 中的 resumeId");
    }

    @Test
    public void streamMessage_should_override_stale_request_resumeId_with_db_resumeId() throws Exception {
        String sessionId = "s-db-wins";
        ChatSession fresh = sessionWith(sessionId, "cli-current", new ArrayList<>());
        when(sessionCache.find(sessionId)).thenReturn(null);
        when(sessionRepository.findById(sessionId)).thenReturn(fresh);

        doAnswer(inv -> {
            IntConsumer onExit = inv.getArgument(8);
            onExit.accept(0);
            return null;
        }).when(gateway).runStream(any(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        service.streamMessage(sessionId, "follow-up", "cli-stale", null, true);

        ArgumentCaptor<String> resumeCap = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), anyString(),
                anyString(), resumeCap.capture(), any(), anyLong(), any(), any(), any());

        assertEquals("cli-current", resumeCap.getValue(), "客户端 resumeId 与 DB 不一致时应以 DB 为准");
    }

    @Test
    public void streamMessage_should_NOT_inject_prefix_on_first_message() throws Exception {
        String sessionId = "s-fresh";
        ChatSession fresh = sessionWith(sessionId, null, new ArrayList<>());
        when(sessionCache.find(sessionId)).thenReturn(null);
        when(sessionRepository.findById(sessionId)).thenReturn(fresh);

        doAnswer(inv -> {
            IntConsumer onExit = inv.getArgument(8);
            onExit.accept(0);
            return null;
        }).when(gateway).runStream(any(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        service.streamMessage(sessionId, "first ever", null, null, true);

        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), anyString(), msgCap.capture(),
                anyString(), any(), any(), anyLong(), any(), any(), any());

        assertFalse(msgCap.getValue().contains("<conversation_history>"),
                "首次发消息历史为空, 不应注入前缀");
        assertTrue(msgCap.getValue().startsWith("first ever"), "首次消息应保留原始用户输入");
        assertTrue(msgCap.getValue().contains("[最终回答要求]"), "普通聊天应追加最终回答要求");
        // 防御性: 确保 service 至少走到了 gateway 调用而不是早返回
        assertNotNull(fresh);
    }
}
