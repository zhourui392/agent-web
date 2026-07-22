package com.example.agentweb.domain.chat;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChatSession} 聚合不变量单测。
 *
 * <p>当前覆盖删除权限规则 {@link ChatSession#requireDeletableBy(String)}：仅创建者可删除自己的会话，
 * 无归属的老数据/公共会话(userId 为 null)允许任意登录用户删除，删他人会话抛
 * {@link SessionDeletionForbiddenException}。该规则独立于可见性隔离开关。</p>
 *
 * @author zhourui(V33215020)
 */
class ChatSessionTest {

    private ChatSession sessionOwnedBy(String owner) {
        ChatSession s = new ChatSession("sess-1", AgentType.CLAUDE, "/tmp/wd",
                Instant.parse("2026-06-23T00:00:00Z"), new ArrayList<>());
        s.setUserId(owner);
        return s;
    }

    @Test
    void owner_can_delete_own_session() {
        ChatSession s = sessionOwnedBy("alice");

        assertDoesNotThrow(() -> s.requireDeletableBy("alice"));
    }

    @Test
    void anyone_can_delete_legacy_ownerless_session() {
        ChatSession s = sessionOwnedBy(null);

        assertDoesNotThrow(() -> s.requireDeletableBy("alice"));
        assertDoesNotThrow(() -> s.requireDeletableBy(null));
    }

    @Test
    void cannot_delete_other_users_session() {
        ChatSession s = sessionOwnedBy("alice");

        assertThrows(SessionDeletionForbiddenException.class, () -> s.requireDeletableBy("bob"));
    }

    @Test
    void anonymous_cannot_delete_owned_session() {
        ChatSession s = sessionOwnedBy("alice");

        assertThrows(SessionDeletionForbiddenException.class, () -> s.requireDeletableBy(null));
    }

    @Test
    void plan_truncation_should_return_user_prefill_and_resume_state() {
        ChatSession session = new ChatSession("sess-1", AgentType.CLAUDE, "/tmp/wd",
                Instant.parse("2026-06-23T00:00:00Z"), Arrays.asList(
                new ChatMessage(10L, "user", "before", Instant.parse("2026-06-23T00:00:00Z")),
                new ChatMessage(11L, "user", "target", Instant.parse("2026-06-23T00:00:01Z")),
                new ChatMessage(12L, "assistant", "answer", Instant.parse("2026-06-23T00:00:02Z"))));
        session.setResumeId("resume-1");

        ChatSessionTruncation plan = session.planTruncationFrom(11L);

        assertEquals("target", plan.getPrefillContent());
        assertEquals(true, plan.isResumeIdPresent());
    }

    @Test
    void plan_truncation_should_not_prefill_assistant_or_missing_message() {
        ChatSession session = new ChatSession("sess-1", AgentType.CLAUDE, "/tmp/wd",
                Instant.parse("2026-06-23T00:00:00Z"), Arrays.asList(
                new ChatMessage(11L, "assistant", "answer", Instant.parse("2026-06-23T00:00:01Z"))));

        assertEquals("", session.planTruncationFrom(11L).getPrefillContent());
        assertEquals("", session.planTruncationFrom(99L).getPrefillContent());
    }
}
