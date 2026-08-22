package com.example.agentweb.domain.chat;

import com.example.agentweb.domain.mode.ModeSnapshot;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ChatSession 模式绑定与切换血缘语义回归。
 *
 * @author alex
 * @since 2026-08-20
 */
class ChatSessionModeSwitchTest {

    private static ModeSnapshot snapshot(String modeId, String displayName) {
        return new ModeSnapshot(modeId, "reviewer", displayName, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void bindModeIsFrozenOnce() {
        ChatSession session = new ChatSession("s1", AgentType.CLAUDE, "/tmp/wd",
                Instant.now(), new ArrayList<>());
        assertNull(session.getModeId());
        session.bindMode(snapshot("m1", "评审"));
        assertEquals("m1", session.getModeId());
        assertEquals("评审", session.getModeSnapshot().getDisplayName());
        assertThrows(IllegalStateException.class,
                () -> session.bindMode(snapshot("m2", "其他")));
    }

    @Test
    void createSwitchedInheritsFactsAndIsolatesResume() {
        ChatSession source = new ChatSession("s1", AgentType.CLAUDE, "/tmp/wd",
                Instant.parse("2026-08-20T10:00:00Z"), new ArrayList<>());
        source.setResumeId("resume-1");
        source.setUserId("u1");
        source.setUserName("用户");
        source.setEnv("test");
        source.setTitle("老标题");
        source.bindMode(snapshot("m1", "评审"));

        ChatSession target = ChatSession.createSwitched(source, "s2",
                snapshot("m2", "实现"), "h1", Instant.parse("2026-08-20T11:00:00Z"));
        assertEquals("s1", target.getSwitchedFromSessionId());
        assertEquals("h1", target.getHandoffDocumentId());
        assertEquals("m2", target.getModeId());
        assertEquals("u1", target.getUserId());
        assertEquals("test", target.getEnv());
        assertEquals("老标题", target.getTitle());
        assertEquals(SessionKind.CHAT, target.getSessionKind());
        // 上下文隔离:新会话显式不继承 resumeId
        assertNull(target.getResumeId());
    }

    @Test
    void createSwitchedToDefaultModeKeepsModeFieldsNull() {
        ChatSession source = new ChatSession("s1", AgentType.CLAUDE, "/tmp/wd",
                Instant.now(), new ArrayList<>());
        ChatSession target = ChatSession.createSwitched(source, "s2", null, "h1",
                Instant.now());
        assertNull(target.getModeId());
        assertNull(target.getModeSnapshot());
        assertEquals("h1", target.getHandoffDocumentId());
    }

    @Test
    void createSwitchedRejectsWorkbenchSource() {
        ChatSession workbench = ChatSession.createWorkbenchStage(
                "s1", AgentType.CLAUDE, "/tmp/wd", "ctx", "u1", "用户", Instant.now());
        assertThrows(IllegalArgumentException.class, () -> ChatSession.createSwitched(
                workbench, "s2", null, "h1", Instant.now()));
    }
}
