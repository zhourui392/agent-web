package com.example.agentweb.domain.chatrun;

import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SessionKind 与 RunOrigin 的跨聚合兼容性测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RunSessionOriginPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");

    @Test
    void onlyMatchingChatAndWorkbenchOriginCombinationsShouldBeAccepted() {
        ChatSession chatSession = new ChatSession(
                "session-1", AgentType.CODEX, "/workspace", NOW,
                Collections.emptyList());
        ChatSession workbenchStageSession = ChatSession.createWorkbenchStage(
                "session-1", AgentType.CODEX, "/workspace",
                "workbench-1:stage-implementation", "owner-1", "Alex", NOW);
        ChatRun chatRun = ChatRun.submit(
                ChatRunId.of("chat-run"), "session-1", 1L, "chat-key", NOW);
        ChatRun workbenchRun = ChatRun.submit(
                ChatRunId.of("workbench-run"), "session-1", 2L,
                "workbench-key", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:stage-implementation",
                        "workbench-run"), NOW);

        assertDoesNotThrow(() -> RunSessionOriginPolicy.requireCompatible(
                chatSession, chatRun));
        assertDoesNotThrow(() -> RunSessionOriginPolicy.requireCompatible(
                workbenchStageSession, workbenchRun));
        ChatRunNotFoundException chatWithWorkbench = assertThrows(
                ChatRunNotFoundException.class,
                () -> RunSessionOriginPolicy.requireCompatible(
                        chatSession, workbenchRun));
        ChatRunNotFoundException workbenchWithChat = assertThrows(
                ChatRunNotFoundException.class,
                () -> RunSessionOriginPolicy.requireCompatible(
                        workbenchStageSession, chatRun));
        assertFalse(chatWithWorkbench.getMessage().contains("WORKBENCH"));
        assertFalse(workbenchWithChat.getMessage().contains("WORKBENCH"));
    }

    @Test
    void matchingKindsShouldStillRejectDifferentSessionIds() {
        ChatSession session = new ChatSession(
                "session-1", AgentType.CODEX, "/workspace", NOW,
                Collections.emptyList());
        ChatRun run = ChatRun.submit(
                ChatRunId.of("chat-run"), "session-2", 1L, "chat-key", NOW);

        assertThrows(ChatRunNotFoundException.class,
                () -> RunSessionOriginPolicy.requireCompatible(session, run));
    }
}
