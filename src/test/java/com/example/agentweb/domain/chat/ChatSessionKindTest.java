package com.example.agentweb.domain.chat;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatSession} 的普通 Chat 与 Workbench Stage 会话不变量测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatSessionKindTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-01T10:00:00Z");
    private static final String STAGE_CONTEXT =
            "workbench-1:stage-implementation";

    @Test
    void sessionKindsShouldContainOnlyChatAndWorkbenchStage() {
        assertArrayEquals(
                new SessionKind[]{
                        SessionKind.CHAT, SessionKind.WORKBENCH_STAGE},
                SessionKind.values());
    }

    @Test
    void ordinaryConstructorShouldCreateChatSessionWithoutContext() {
        ChatSession chat = new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>());

        assertEquals(SessionKind.CHAT, chat.getSessionKind());
        assertNull(chat.getContextId());
        assertNull(chat.getRetiredAt());
    }

    @Test
    void createWorkbenchStageShouldBindServerFactsAndFullOwner() {
        ChatSession session = stageSession();

        assertEquals("stage-session-1", session.getId());
        assertEquals(AgentType.CODEX, session.getAgentType());
        assertEquals("/workspace/product", session.getWorkingDir());
        assertEquals(SessionKind.WORKBENCH_STAGE, session.getSessionKind());
        assertEquals(STAGE_CONTEXT, session.getContextId());
        assertEquals("owner-1", session.getUserId());
        assertEquals("Alex", session.getUserName());
        assertEquals(CREATED_AT, session.getCreatedAt());
        assertNull(session.getRetiredAt());
    }

    @Test
    void createWorkbenchStageShouldRejectMissingContextOrOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatSession.createWorkbenchStage(
                        "stage-session-1", AgentType.CODEX, "/workspace",
                        " ", "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ChatSession.createWorkbenchStage(
                        "stage-session-1", AgentType.CODEX, "/workspace",
                        STAGE_CONTEXT, " ", "Alex", CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ChatSession.createWorkbenchStage(
                        "stage-session-1", AgentType.CODEX, "/workspace",
                        STAGE_CONTEXT, "owner-1", " ", CREATED_AT));
    }

    @Test
    void restoredFactsShouldKeepKindAndContextConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>(), SessionKind.CHAT,
                "forbidden-context", null));
        assertThrows(IllegalArgumentException.class, () -> new ChatSession(
                "stage-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>(), SessionKind.WORKBENCH_STAGE,
                " ", null));
    }

    @Test
    void retireShouldBeWorkbenchOnlyIdempotentAndKeepFirstTime() {
        ChatSession stage = stageSession();
        Instant retiredAt = CREATED_AT.plusSeconds(30);

        assertTrue(stage.retire(retiredAt));
        assertFalse(stage.retire(retiredAt.plusSeconds(30)));
        assertEquals(retiredAt, stage.getRetiredAt());

        ChatSession chat = new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>());
        assertThrows(IllegalStateException.class,
                () -> chat.retire(retiredAt));
    }

    @Test
    void retireShouldRejectTimeBeforeCreation() {
        ChatSession stage = stageSession();

        assertThrows(IllegalArgumentException.class,
                () -> stage.retire(CREATED_AT.minusMillis(1)));
        assertNull(stage.getRetiredAt());
    }

    @Test
    void activeWorkbenchStageShouldValidateEveryStableFact() {
        ChatSession stage = stageSession();
        stage.setEnv("local");

        assertDoesNotThrow(() -> requireExpectedStage(stage));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "other-session", AgentType.CODEX,
                        "/workspace/product", "local", STAGE_CONTEXT,
                        "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "stage-session-1", AgentType.CLAUDE,
                        "/workspace/product", "local", STAGE_CONTEXT,
                        "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "stage-session-1", AgentType.CODEX,
                        "/other", "local", STAGE_CONTEXT,
                        "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "stage-session-1", AgentType.CODEX,
                        "/workspace/product", "prod", STAGE_CONTEXT,
                        "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "stage-session-1", AgentType.CODEX,
                        "/workspace/product", "local",
                        "workbench-1:stage-other",
                        "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "stage-session-1", AgentType.CODEX,
                        "/workspace/product", "local", STAGE_CONTEXT,
                        "owner-2", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "stage-session-1", AgentType.CODEX,
                        "/workspace/product", "local", STAGE_CONTEXT,
                        "owner-1", "Other", CREATED_AT));
        assertThrows(IllegalStateException.class,
                () -> stage.requireActiveWorkbenchStage(
                        "stage-session-1", AgentType.CODEX,
                        "/workspace/product", "local", STAGE_CONTEXT,
                        "owner-1", "Alex", CREATED_AT.plusSeconds(1)));

        stage.retire(CREATED_AT.plusSeconds(1));
        assertThrows(IllegalStateException.class,
                () -> requireExpectedStage(stage));
    }

    @Test
    void ordinaryChatGuardShouldHideActiveAndRetiredWorkbenchStages() {
        ChatSession chat = new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>());
        ChatSession activeStage = stageSession();
        ChatSession retiredStage = ChatSession.createWorkbenchStage(
                "stage-session-retired", AgentType.CODEX,
                "/workspace/product", "workbench-1:stage-analysis",
                "owner-1", "Alex", CREATED_AT);
        retiredStage.retire(CREATED_AT.plusSeconds(1));

        assertDoesNotThrow(chat::requireOrdinaryChat);
        ChatSessionNotFoundException activeError = assertThrows(
                ChatSessionNotFoundException.class,
                activeStage::requireOrdinaryChat);
        ChatSessionNotFoundException retiredError = assertThrows(
                ChatSessionNotFoundException.class,
                retiredStage::requireOrdinaryChat);
        assertFalse(activeError.getMessage().contains("WORKBENCH_STAGE"));
        assertFalse(retiredError.getMessage().contains("WORKBENCH_STAGE"));
    }

    private ChatSession stageSession() {
        return ChatSession.createWorkbenchStage(
                "stage-session-1", AgentType.CODEX, "/workspace/product",
                STAGE_CONTEXT, "owner-1", "Alex", CREATED_AT);
    }

    private void requireExpectedStage(ChatSession stage) {
        stage.requireActiveWorkbenchStage(
                "stage-session-1", AgentType.CODEX, "/workspace/product",
                "local", STAGE_CONTEXT, "owner-1", "Alex", CREATED_AT);
    }
}
