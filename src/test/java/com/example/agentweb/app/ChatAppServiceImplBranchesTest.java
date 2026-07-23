package com.example.agentweb.app;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatAppServiceImpl 的会话生命周期与回退编排分支测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-27
 */
class ChatAppServiceImplBranchesTest {

    private SessionCache sessionCache;
    private SessionRepository sessionRepository;
    private SlashCommandExpander commandExpander;
    private ChatAgentDefaults chatAgentDefaults;
    private UploadPicStorage uploadPicStore;
    private UploadFileStorage uploadFileStore;
    private WorkspacePathPolicy workspacePathPolicy;
    private ChatRunActivityGuard chatRunActivityGuard;
    private ChatAppServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sessionCache = mock(SessionCache.class);
        sessionRepository = mock(SessionRepository.class);
        commandExpander = mock(SlashCommandExpander.class);
        chatAgentDefaults = mock(ChatAgentDefaults.class);
        uploadPicStore = mock(UploadPicStorage.class);
        uploadFileStore = mock(UploadFileStorage.class);
        workspacePathPolicy = mock(WorkspacePathPolicy.class);
        chatRunActivityGuard = mock(ChatRunActivityGuard.class);
        when(workspacePathPolicy.requireExistingDirectory(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, mock(AgentGateway.class),
                commandExpander, chatAgentDefaults, uploadPicStore, uploadFileStore,
                Optional.empty(),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> Optional.empty()));
        service.configureWorkspacePathPolicy(workspacePathPolicy);
        service.configureChatRunActivityGuard(chatRunActivityGuard);
    }

    @Test
    void listCommands_sessionNotFound_throws() {
        when(sessionRepository.findById("nope")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.listCommands("nope"));
    }

    @Test
    void listCommands_found_delegatesToExpander() {
        ChatSession session = session("s1", "/tmp/wd");
        SlashCommand command = new SlashCommand("greet", "say hi", null, "Hello {{args}}", false);
        when(sessionCache.find("s1")).thenReturn(session);
        when(commandExpander.listCommands("/tmp/wd")).thenReturn(Collections.singletonList(command));

        List<SlashCommand> result = service.listCommands("s1");

        assertEquals(1, result.size());
        assertSame(command, result.get(0));
    }

    @Test
    void getSession_cacheHit_returnsDirectly() {
        ChatSession session = session("s1", "/tmp/wd");
        when(sessionCache.find("s1")).thenReturn(session);

        assertSame(session, service.getSession("s1"));
        verify(sessionRepository, never()).findById(any());
    }

    @Test
    void getSession_cacheMiss_loadsFromRepoAndCaches() {
        ChatSession session = session("s1", "/tmp/wd");
        when(sessionRepository.findById("s1")).thenReturn(session);

        assertSame(session, service.getSession("s1"));
        verify(sessionCache).save(session);
    }

    @Test
    void getSession_cacheMissAndRepoEmpty_returnsNull() {
        assertNull(service.getSession("nope"));
        verify(sessionCache, never()).save(any());
    }

    @Test
    void sendMessage_sessionNotFound_throws() {
        SendMessageRequest request = new SendMessageRequest();
        request.setMessage("hi");

        assertThrows(IllegalArgumentException.class,
                () -> service.sendMessage("nope", request));
    }

    @Test
    void startSession_workingDirNotExists_throws() {
        StartSessionRequest request = startRequest(tempDir.resolve("nonexistent").toString());
        when(workspacePathPolicy.requireExistingDirectory(request.getWorkingDir()))
                .thenThrow(new IllegalArgumentException("Path out of allowed roots"));

        assertThrows(IllegalArgumentException.class,
                () -> service.startSession(request, "1.2.3.4"));
    }

    @Test
    void startSession_should_use_canonicalAllowedWorkingDirectory() {
        StartSessionRequest request = startRequest(tempDir.resolve("alias").toString());
        when(workspacePathPolicy.requireExistingDirectory(request.getWorkingDir()))
                .thenReturn(tempDir.toString());

        ChatSession session = service.startSession(request, null);

        assertEquals(tempDir.toString(), session.getWorkingDir());
    }

    @Test
    void startSession_envEmpty_doesNotPersistEnv() {
        StartSessionRequest request = startRequest(tempDir.toString());
        request.setEnv("");

        ChatSession session = service.startSession(request, null);

        assertNull(session.getEnv());
        verify(sessionRepository).saveSession(session);
    }

    @Test
    void startSession_envProvided_persistsEnv() {
        StartSessionRequest request = startRequest(tempDir.toString());
        request.setEnv("test");

        ChatSession session = service.startSession(request, "1.2.3.4");

        assertEquals("test", session.getEnv());
    }

    @Test
    void startSession_clientIpProvided_persistsClientIp() {
        StartSessionRequest request = startRequest(tempDir.toString());

        ChatSession session = service.startSession(request, "9.9.9.9");

        assertEquals("9.9.9.9", session.getClientIp());
    }

    @Test
    void startSession_clientIpBlank_doesNotPersistClientIp() {
        StartSessionRequest request = startRequest(tempDir.toString());

        ChatSession session = service.startSession(request, "   ");

        assertNull(session.getClientIp());
    }

    @Test
    void truncateFrom_sessionNotFound_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.truncateFrom("nope", 1L));
        verify(chatRunActivityGuard, never()).requireInactive(anyString());
    }

    @Test
    void truncateFrom_activeRun_rejectsAfterAuthorizingSession() {
        when(sessionRepository.findById("s1")).thenReturn(session("s1", "/tmp/wd"));
        doThrow(new ActiveChatRunExistsException("s1"))
                .when(chatRunActivityGuard).requireInactive("s1");

        assertThrows(ActiveChatRunExistsException.class,
                () -> service.truncateFrom("s1", 1L));

        org.mockito.InOrder order = inOrder(sessionRepository, chatRunActivityGuard);
        order.verify(sessionRepository).findById("s1");
        order.verify(chatRunActivityGuard).requireInactive("s1");
        verify(sessionRepository, never()).truncateFrom(anyString(), anyLong());
    }

    @Test
    void truncateFrom_idMatchesUserMessage_prefillReturned() {
        ChatSession session = sessionWithMessages("s1", "/tmp",
                messageWithId("user", "前一句", 10L),
                messageWithId("user", "目标消息", 11L),
                messageWithId("assistant", "答", 12L));
        session.setResumeId("claude-r");
        when(sessionRepository.findById("s1")).thenReturn(session);
        when(sessionRepository.truncateFrom("s1", 11L)).thenReturn(2);

        TruncateResult result = service.truncateFrom("s1", 11L);

        assertEquals(2, result.getDeletedCount());
        assertEquals("目标消息", result.getPrefillContent());
        assertTrue(result.isResumeIdCleared());
        verify(sessionCache).remove("s1");
    }

    @Test
    void truncateFrom_idMatchesAssistantMessage_emptyPrefill() {
        ChatSession session = sessionWithMessages("s1", "/tmp",
                messageWithId("user", "Q", 10L),
                messageWithId("assistant", "目标", 11L));
        when(sessionRepository.findById("s1")).thenReturn(session);
        when(sessionRepository.truncateFrom("s1", 11L)).thenReturn(1);

        TruncateResult result = service.truncateFrom("s1", 11L);

        assertEquals("", result.getPrefillContent());
        assertFalse(result.isResumeIdCleared());
    }

    @Test
    void truncateFrom_noMatchingId_emptyPrefill() {
        ChatSession session = sessionWithMessages("s1", "/tmp",
                messageWithId("user", "Q", 10L));
        when(sessionRepository.findById("s1")).thenReturn(session);
        when(sessionRepository.truncateFrom("s1", 999L)).thenReturn(0);

        TruncateResult result = service.truncateFrom("s1", 999L);

        assertEquals("", result.getPrefillContent());
        assertEquals(0, result.getDeletedCount());
    }

    @Test
    void deleteSession_noPersistedSession_stillRemovesCacheAndRepo() {
        service.deleteSession("orphan");

        verify(sessionCache).remove("orphan");
        verify(sessionRepository).deleteById("orphan");
        verify(uploadPicStore, never()).deleteSessionImages(any(), any());
        verify(uploadFileStore, never()).deleteSessionFiles(any(), any());
    }

    private StartSessionRequest startRequest(String workingDir) {
        StartSessionRequest request = new StartSessionRequest();
        request.setAgentType("CLAUDE");
        request.setWorkingDir(workingDir);
        return request;
    }

    private ChatSession session(String id, String workingDir) {
        return new ChatSession(id, AgentType.CLAUDE, workingDir, Instant.now(),
                Collections.emptyList());
    }

    private ChatSession sessionWithMessages(String id, String workingDir, ChatMessage... messages) {
        return new ChatSession(id, AgentType.CLAUDE, workingDir, Instant.now(),
                new ArrayList<ChatMessage>(Arrays.asList(messages)));
    }

    private ChatMessage messageWithId(String role, String content, long id) {
        ChatMessage message = new ChatMessage(role, content);
        try {
            Field field = ChatMessage.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(message, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return message;
    }
}
