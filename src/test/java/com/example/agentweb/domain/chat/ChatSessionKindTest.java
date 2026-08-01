package com.example.agentweb.domain.chat;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatSession} 的中性会话种类、上下文和退役不变量测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatSessionKindTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:00:00Z");

    @Test
    void existingConstructorsShouldRemainBackwardCompatibleChatSessions() {
        ChatSession restoredLegacy = new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT, new ArrayList<ChatMessage>());

        assertEquals(SessionKind.CHAT, restoredLegacy.getSessionKind());
        assertNull(restoredLegacy.getContextId());
        assertNull(restoredLegacy.getRetiredAt());
    }

    @Test
    void createWorkbenchPhaseShouldUseServerProvidedStableFactsAndOwner() {
        ChatSession session = ChatSession.createWorkbenchPhase(
                "phase-session-1",
                AgentType.CODEX,
                "/workspace/product",
                "workbench-1:IMPLEMENT_TEST",
                "owner-1",
                "Alex",
                CREATED_AT);

        assertEquals("phase-session-1", session.getId());
        assertEquals(AgentType.CODEX, session.getAgentType());
        assertEquals("/workspace/product", session.getWorkingDir());
        assertEquals(SessionKind.WORKBENCH_PHASE, session.getSessionKind());
        assertEquals("workbench-1:IMPLEMENT_TEST", session.getContextId());
        assertEquals("owner-1", session.getUserId());
        assertEquals("Alex", session.getUserName());
        assertEquals(CREATED_AT, session.getCreatedAt());
        assertNull(session.getRetiredAt());
    }

    @Test
    void createWorkbenchPhaseShouldRejectMissingContextOrOwner() {
        assertThrows(IllegalArgumentException.class, () -> ChatSession.createWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", " ",
                "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> ChatSession.createWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "workbench-1:SOLUTION_DESIGN",
                " ", "Alex", CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> ChatSession.createWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "workbench-1:SOLUTION_DESIGN",
                "owner-1", " ", CREATED_AT));
    }

    @Test
    void restoredFactsShouldKeepKindAndContextConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>(), SessionKind.CHAT, "forbidden-context", null));
        assertThrows(IllegalArgumentException.class, () -> new ChatSession(
                "phase-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>(), SessionKind.WORKBENCH_PHASE, " ", null));
    }

    @Test
    void retireShouldBeWorkbenchOnlyIdempotentAndKeepFirstRetirementTime() {
        ChatSession phase = ChatSession.createWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace",
                "workbench-1:REVIEW_REFACTOR", "owner-1", "Alex", CREATED_AT);
        Instant retiredAt = CREATED_AT.plusSeconds(30);

        assertTrue(phase.retire(retiredAt));
        assertFalse(phase.retire(retiredAt.plusSeconds(30)));
        assertEquals(retiredAt, phase.getRetiredAt());

        ChatSession chat = new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT, new ArrayList<ChatMessage>());
        assertThrows(IllegalStateException.class, () -> chat.retire(retiredAt));
    }

    @Test
    void retireShouldRejectTimeBeforeCreation() {
        ChatSession phase = ChatSession.createWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace",
                "workbench-1:REQUIREMENT_ANALYSIS", "owner-1", "Alex", CREATED_AT);

        assertThrows(IllegalArgumentException.class, () -> phase.retire(CREATED_AT.minusMillis(1)));
        assertNull(phase.getRetiredAt());
    }

    @Test
    void requireActiveWorkbenchPhaseShouldValidateKindContextAndFullOwner() {
        ChatSession phase = ChatSession.createWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT);
        phase.setEnv("local");

        assertDoesNotThrow(() -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "other-session", AgentType.CODEX, "/workspace", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CLAUDE, "/workspace", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/other", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "prod",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT.plusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "local",
                "workbench-1:SOLUTION_DESIGN", "owner-1", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-2", "Alex", CREATED_AT));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Other", CREATED_AT));

        phase.retire(CREATED_AT.plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> phase.requireActiveWorkbenchPhase(
                "phase-session-1", AgentType.CODEX, "/workspace", "local",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT));

        ChatSession chat = new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>());
        assertThrows(IllegalStateException.class, () -> chat.requireActiveWorkbenchPhase(
                "chat-1", AgentType.CLAUDE, "/workspace", null,
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT));
    }

    @Test
    void ordinaryChatGuardShouldAllowChatAndHideActiveOrRetiredWorkbenchSessions() {
        ChatSession chat = new ChatSession(
                "chat-1", AgentType.CLAUDE, "/workspace", CREATED_AT,
                new ArrayList<ChatMessage>());
        ChatSession activePhase = ChatSession.createWorkbenchPhase(
                "phase-session-active", AgentType.CODEX, "/workspace",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT);
        ChatSession retiredPhase = ChatSession.createWorkbenchPhase(
                "phase-session-retired", AgentType.CODEX, "/workspace",
                "workbench-1:SOLUTION_DESIGN", "owner-1", "Alex", CREATED_AT);
        retiredPhase.retire(CREATED_AT.plusSeconds(1));

        assertDoesNotThrow(chat::requireOrdinaryChat);
        ChatSessionNotFoundException activeError = assertThrows(
                ChatSessionNotFoundException.class, activePhase::requireOrdinaryChat);
        ChatSessionNotFoundException retiredError = assertThrows(
                ChatSessionNotFoundException.class, retiredPhase::requireOrdinaryChat);
        assertFalse(activeError.getMessage().contains("WORKBENCH_PHASE"));
        assertFalse(retiredError.getMessage().contains("WORKBENCH_PHASE"));
    }
}
