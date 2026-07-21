package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.ChatProperties;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import com.example.agentweb.interfaces.dto.TruncateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补 ChatAppServiceImpl 边缘分支:
 * stopSession / isSessionRunning / listCommands / getSession 缓存路径 /
 * resolveHistoryForPrefix 各分支 / truncateFrom session not found + role 路由。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-27
 */
public class ChatAppServiceImplBranchesTest {

    private SessionCache sessionCache;
    private SessionRepository sessionRepository;
    private AgentGateway gateway;
    private SlashCommandExpander commandExpander;
    private StreamOutputExtractor outputExtractor;
    private AgentTypeResolver agentTypeResolver;
    private UploadPicStore uploadPicStore;
    private UploadFileStore uploadFileStore;
    private ChatAppServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setUp() {
        sessionCache = mock(SessionCache.class);
        sessionRepository = mock(SessionRepository.class);
        gateway = mock(AgentGateway.class);
        commandExpander = mock(SlashCommandExpander.class);
        outputExtractor = mock(StreamOutputExtractor.class);
        agentTypeResolver = mock(AgentTypeResolver.class);
        uploadPicStore = mock(UploadPicStore.class);
        uploadFileStore = mock(UploadFileStore.class);
        Executor sync = Runnable::run;
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, gateway, sync,
                commandExpander, outputExtractor, agentTypeResolver, uploadPicStore, uploadFileStore,
                java.util.Optional.empty(),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> java.util.Optional.empty()));
    }

    private void rebuildService(ChatProperties chatProperties) {
        Executor sync = Runnable::run;
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, gateway, sync,
                commandExpander, outputExtractor, agentTypeResolver, uploadPicStore, uploadFileStore,
                java.util.Optional.empty(), java.util.Optional.empty(),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> java.util.Optional.empty()),
                chatProperties);
    }

    // ============ stopSession / isSessionRunning ============

    @Test
    public void stopSession_delegatesToGatewayStopStream() {
        service.stopSession("s1");
        verify(gateway).stopStream("s1");
    }

    @Test
    public void isSessionRunning_delegatesToGatewayIsRunning() {
        when(gateway.isRunning("s1")).thenReturn(true);
        assertTrue(service.isSessionRunning("s1"));
        when(gateway.isRunning("s2")).thenReturn(false);
        assertFalse(service.isSessionRunning("s2"));
    }

    // ============ listCommands ============

    @Test
    public void listCommands_sessionNotFound_throws() {
        when(sessionCache.find("nope")).thenReturn(null);
        when(sessionRepository.findById("nope")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.listCommands("nope"));
    }

    @Test
    public void listCommands_found_delegatesToExpander() {
        ChatSession s = session("s1", "/tmp/wd");
        when(sessionCache.find("s1")).thenReturn(s);
        SlashCommand cmd = new SlashCommand("greet", "say hi", null, "Hello {{args}}", false);
        when(commandExpander.listCommands("/tmp/wd")).thenReturn(Collections.singletonList(cmd));

        List<SlashCommand> got = service.listCommands("s1");
        assertEquals(1, got.size());
        assertSame(cmd, got.get(0));
    }

    // ============ getSession 缓存路径 ============

    @Test
    public void getSession_cacheHit_returnsDirectly() {
        ChatSession s = session("s1", "/tmp/wd");
        when(sessionCache.find("s1")).thenReturn(s);

        assertSame(s, service.getSession("s1"));
        verify(sessionRepository, never()).findById(any());
    }

    @Test
    public void getSession_cacheMiss_loadsFromRepoAndCaches() {
        ChatSession s = session("s1", "/tmp/wd");
        when(sessionCache.find("s1")).thenReturn(null);
        when(sessionRepository.findById("s1")).thenReturn(s);

        ChatSession got = service.getSession("s1");

        assertSame(s, got);
        verify(sessionCache).save(s);
    }

    @Test
    public void getSession_cacheMissAndRepoEmpty_returnsNull() {
        when(sessionCache.find("nope")).thenReturn(null);
        when(sessionRepository.findById("nope")).thenReturn(null);
        assertNull(service.getSession("nope"));
        verify(sessionCache, never()).save(any());
    }

    // ============ sendMessage / streamMessage session not found ============

    @Test
    public void sendMessage_sessionNotFound_throws() {
        when(sessionCache.find("nope")).thenReturn(null);
        when(sessionRepository.findById("nope")).thenReturn(null);
        SendMessageRequest req = new SendMessageRequest();
        req.setMessage("hi");
        assertThrows(IllegalArgumentException.class,
                () -> service.sendMessage("nope", req));
    }

    @Test
    public void streamMessage_sessionNotFound_throws() {
        when(sessionCache.find("nope")).thenReturn(null);
        when(sessionRepository.findById("nope")).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.streamMessage("nope", "hi", null, null, true));
    }

    // ============ startSession 各分支 ============

    @Test
    public void startSession_workingDirNotExists_throws() {
        StartSessionRequest req = new StartSessionRequest();
        req.setAgentType("CLAUDE");
        req.setWorkingDir(tempDir.resolve("nonexistent").toString());
        when(agentTypeResolver.resolve("CLAUDE")).thenReturn(AgentType.CLAUDE);

        assertThrows(IllegalArgumentException.class, () -> service.startSession(req, "1.2.3.4"));
    }

    @Test
    public void startSession_envEmpty_doesNotPersistEnv() {
        StartSessionRequest req = new StartSessionRequest();
        req.setAgentType("CLAUDE");
        req.setWorkingDir(tempDir.toString());
        req.setEnv(""); // empty 不设置
        when(agentTypeResolver.resolve("CLAUDE")).thenReturn(AgentType.CLAUDE);

        ChatSession s = service.startSession(req, null);

        assertNull(s.getEnv());
        verify(sessionCache).save(s);
        verify(sessionRepository).saveSession(s);
    }

    @Test
    public void startSession_envProvided_persistsEnv() {
        StartSessionRequest req = new StartSessionRequest();
        req.setAgentType("CLAUDE");
        req.setWorkingDir(tempDir.toString());
        req.setEnv("test");
        when(agentTypeResolver.resolve("CLAUDE")).thenReturn(AgentType.CLAUDE);

        ChatSession s = service.startSession(req, "1.2.3.4");
        assertEquals("test", s.getEnv());
    }

    @Test
    public void startSession_clientIpProvided_persistsClientIp() {
        StartSessionRequest req = new StartSessionRequest();
        req.setAgentType("CLAUDE");
        req.setWorkingDir(tempDir.toString());
        when(agentTypeResolver.resolve("CLAUDE")).thenReturn(AgentType.CLAUDE);

        ChatSession s = service.startSession(req, "9.9.9.9");

        assertEquals("9.9.9.9", s.getClientIp());
        verify(sessionRepository).saveSession(s);
    }

    @Test
    public void startSession_clientIpBlank_doesNotPersistClientIp() {
        StartSessionRequest req = new StartSessionRequest();
        req.setAgentType("CLAUDE");
        req.setWorkingDir(tempDir.toString());
        when(agentTypeResolver.resolve("CLAUDE")).thenReturn(AgentType.CLAUDE);

        ChatSession s = service.startSession(req, "   ");

        assertNull(s.getClientIp());
    }

    // ============ resolveHistoryForPrefix 通过 streamMessage 触发 ============

    @Test
    public void streamMessage_withRequestResumeId_skipsHistoryPrefix() throws Exception {
        ChatSession cached = session("s1", tempDir.toString());
        ChatSession fresh = sessionWithMessages("s1", tempDir.toString(),
                new ChatMessage("user", "之前问的"));
        fresh.setResumeId("claude-resume-1");
        when(sessionCache.find("s1")).thenReturn(cached);
        when(sessionRepository.findById("s1")).thenReturn(fresh);
        when(commandExpander.expandIfCommand(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        service.streamMessage("s1", "hi", "claude-resume-1", null, true);

        org.mockito.ArgumentCaptor<String> msgCap = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> resumeCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), any(), msgCap.capture(), eq("s1"), resumeCap.capture(), any(),
                anyLong(), any(), any(), any());
        assertEquals("claude-resume-1", resumeCap.getValue());
        assertFalse(msgCap.getValue().contains("<conversation_history>"),
                "DB 端已有 resumeId 时不应注入 history 前缀");
        verify(sessionRepository, atLeastOnce()).addMessageReturningId(eq("s1"), any());
    }

    @Test
    public void streamMessage_dbSessionMissing_noHistoryPrefix() {
        ChatSession s = session("s1", tempDir.toString());
        when(sessionCache.find("s1")).thenReturn(s);
        when(sessionRepository.findById("s1")).thenReturn(null);

        service.streamMessage("s1", "hi", null, null, true);

        verify(sessionRepository, atLeastOnce()).addMessageReturningId(eq("s1"), any());
        // 没有 historySnapshot 注入 → 直接走原文
    }

    @Test
    public void streamMessage_dbResumeIdPresent_noHistoryPrefix() {
        ChatSession cached = session("s1", tempDir.toString());
        ChatSession fresh = session("s1", tempDir.toString());
        fresh.setResumeId("claude-existing");
        when(sessionCache.find("s1")).thenReturn(cached);
        when(sessionRepository.findById("s1")).thenReturn(fresh);

        service.streamMessage("s1", "hi", null, null, true);

        // DB 端 resumeId 非空 → 不注入 history prefix
        verify(sessionRepository, atLeastOnce()).addMessageReturningId(eq("s1"), any());
    }

    @Test
    public void streamMessage_dbHistoryEmpty_noHistoryPrefix() {
        ChatSession cached = session("s1", tempDir.toString());
        ChatSession fresh = session("s1", tempDir.toString());
        // resumeId null + messages 为空 → resolveHistoryForPrefix 返回 null
        when(sessionCache.find("s1")).thenReturn(cached);
        when(sessionRepository.findById("s1")).thenReturn(fresh);

        service.streamMessage("s1", "hi", null, null, true);
    }

    @Test
    public void streamMessage_should_appendFinalAnswerInstructionByDefault() throws Exception {
        ChatSession cached = session("s1", tempDir.toString());
        ChatSession fresh = session("s1", tempDir.toString());
        when(sessionCache.find("s1")).thenReturn(cached);
        when(sessionRepository.findById("s1")).thenReturn(fresh);
        when(commandExpander.expandIfCommand(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        service.streamMessage("s1", "hi", null, null, true);

        org.mockito.ArgumentCaptor<String> capt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), any(), capt.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        assertTrue(capt.getValue().contains("[最终回答要求]"));
        assertTrue(capt.getValue().contains("证据摘要"));
    }

    @Test
    public void streamMessage_should_notAppendFinalAnswerInstructionWhenDisabled() throws Exception {
        ChatProperties props = new ChatProperties();
        props.setFinalAnswerInstructionEnabled(false);
        rebuildService(props);
        ChatSession cached = session("s1", tempDir.toString());
        ChatSession fresh = session("s1", tempDir.toString());
        when(sessionCache.find("s1")).thenReturn(cached);
        when(sessionRepository.findById("s1")).thenReturn(fresh);
        when(commandExpander.expandIfCommand(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        service.streamMessage("s1", "hi", null, null, true);

        org.mockito.ArgumentCaptor<String> capt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), any(), capt.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        assertFalse(capt.getValue().contains("[最终回答要求]"));
        assertEquals("hi", capt.getValue());
    }

    @Test
    public void streamMessage_dbHistoryNonEmpty_injectsPrefix() throws Exception {
        ChatSession cached = session("s1", tempDir.toString());
        ChatSession fresh = sessionWithMessages("s1", tempDir.toString(),
                new ChatMessage("user", "之前问的"),
                new ChatMessage("assistant", "之前答的"));
        when(sessionCache.find("s1")).thenReturn(cached);
        when(sessionRepository.findById("s1")).thenReturn(fresh);
        when(commandExpander.expandIfCommand(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(outputExtractor.extractPlainText(any())).thenAnswer(inv -> inv.getArgument(0));

        service.streamMessage("s1", "新问题", null, null, true);

        // 验证 gateway.runStream 收到带 prefix 的 message
        org.mockito.ArgumentCaptor<String> capt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), any(), capt.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        String sent = capt.getValue();
        assertTrue(sent.contains("<conversation_history>"), "应注入 history 前缀: " + sent);
        assertTrue(sent.contains("之前问的"));
        assertTrue(sent.contains("<new_user_message>"));
        assertTrue(sent.contains("新问题"));
    }

    @Test
    public void streamMessage_historyMessageWithEmptyText_skipped() throws Exception {
        // 走 buildHistoryPrefix 的 continue 分支: text 为空时跳过
        ChatSession cached = session("s1", tempDir.toString());
        ChatSession fresh = sessionWithMessages("s1", tempDir.toString(),
                new ChatMessage("user", ""),
                new ChatMessage("user", "有内容"));
        when(sessionCache.find("s1")).thenReturn(cached);
        when(sessionRepository.findById("s1")).thenReturn(fresh);
        when(commandExpander.expandIfCommand(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(outputExtractor.extractPlainText(any())).thenAnswer(inv -> inv.getArgument(0));

        service.streamMessage("s1", "Q", null, null, true);

        org.mockito.ArgumentCaptor<String> capt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).runStream(any(), any(), capt.capture(), eq("s1"), any(), any(),
                anyLong(), any(), any(), any());
        String sent = capt.getValue();
        assertTrue(sent.contains("有内容"));
        // 不应有连续两个空 [user] 块, 空内容那条应被跳过
        int idx = sent.indexOf("[user]:");
        assertTrue(idx > 0);
        // 第二次出现 [user] 的位置不应紧邻第一个 (空内容被 skip)
    }

    // ============ truncateFrom ============

    @Test
    public void truncateFrom_sessionNotFound_throws() {
        when(sessionRepository.findById("nope")).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.truncateFrom("nope", 1L));
    }

    @Test
    public void truncateFrom_idMatchesUserMessage_prefillReturned() {
        ChatSession s = sessionWithMessages("s1", "/tmp",
                buildMessageWithId("user", "前一句", 10L),
                buildMessageWithId("user", "目标消息", 11L),
                buildMessageWithId("assistant", "答", 12L));
        s.setResumeId("claude-r");
        when(sessionRepository.findById("s1")).thenReturn(s);
        when(sessionRepository.truncateFrom("s1", 11L)).thenReturn(2);

        TruncateResult r = service.truncateFrom("s1", 11L);

        assertEquals(2, r.getDeletedCount());
        assertEquals("目标消息", r.getPrefillContent());
        assertTrue(r.isResumeIdCleared());
        verify(sessionCache).remove("s1");
    }

    @Test
    public void truncateFrom_idMatchesAssistantMessage_emptyPrefill() {
        ChatSession s = sessionWithMessages("s1", "/tmp",
                buildMessageWithId("user", "Q", 10L),
                buildMessageWithId("assistant", "目标", 11L));
        when(sessionRepository.findById("s1")).thenReturn(s);
        when(sessionRepository.truncateFrom("s1", 11L)).thenReturn(1);

        TruncateResult r = service.truncateFrom("s1", 11L);

        // 命中的是 assistant 消息 → prefill 保持空
        assertEquals("", r.getPrefillContent());
        assertFalse(r.isResumeIdCleared());
    }

    @Test
    public void truncateFrom_noMatchingId_emptyPrefill() {
        ChatSession s = sessionWithMessages("s1", "/tmp",
                buildMessageWithId("user", "Q", 10L));
        when(sessionRepository.findById("s1")).thenReturn(s);
        when(sessionRepository.truncateFrom("s1", 999L)).thenReturn(0);

        TruncateResult r = service.truncateFrom("s1", 999L);

        assertEquals("", r.getPrefillContent());
        assertEquals(0, r.getDeletedCount());
    }

    // ============ deleteSession 分支: session 不存在 ============

    @Test
    public void deleteSession_noPersistedSession_stillRemovesCacheAndRepo() {
        when(sessionRepository.findById("orphan")).thenReturn(null);

        service.deleteSession("orphan");

        // 即使 session 不存在, 仍清缓存 + 调 delete (幂等)
        verify(sessionCache).remove("orphan");
        verify(sessionRepository).deleteById("orphan");
        // 不应调清图/清附件
        verify(uploadPicStore, never()).deleteSessionImages(any(), any());
        verify(uploadFileStore, never()).deleteSessionFiles(any(), any());
    }

    // ============ helpers ============

    private ChatSession session(String id, String workingDir) {
        return new ChatSession(id, AgentType.CLAUDE, workingDir, Instant.now(), new ArrayList<>());
    }

    /** 用构造器一次性塞入消息: getMessages() 是 unmodifiableList, 不能事后 add。 */
    private ChatSession sessionWithMessages(String id, String workingDir, ChatMessage... msgs) {
        List<ChatMessage> list = new ArrayList<>(Arrays.asList(msgs));
        return new ChatSession(id, AgentType.CLAUDE, workingDir, Instant.now(), list);
    }

    /** ChatMessage.id 通常由 DB 回填, 这里用反射注入便于测试。 */
    private ChatMessage buildMessageWithId(String role, String content, long id) {
        ChatMessage m = new ChatMessage(role, content);
        try {
            Field f = ChatMessage.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    static {
        assertNotNull("loaded");
    }
}
