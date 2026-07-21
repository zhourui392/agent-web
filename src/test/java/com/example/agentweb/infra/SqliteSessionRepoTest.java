package com.example.agentweb.infra;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.chat.FeedbackRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-25
 */
public class SqliteSessionRepoTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteSessionRepo repo;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("session-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        // 与 schema.sql + SqliteInitializer migration 对齐的最终列结构, 一次性建好避免重复 ALTER
        jdbc.execute(
                "CREATE TABLE chat_session ("
                        + "id TEXT PRIMARY KEY,"
                        + "agent_type TEXT NOT NULL,"
                        + "working_dir TEXT NOT NULL,"
                        + "created_at TEXT NOT NULL,"
                        + "resume_id TEXT,"
                        + "share_token TEXT,"
                        + "env TEXT,"
                        + "title TEXT,"
                        + "feedback_rating TEXT,"
                        + "feedback_comment TEXT,"
                        + "feedback_at TEXT,"
                        + "last_message_at INTEGER,"
                        + "client_ip TEXT,"
                        + "user_id TEXT,"
                        + "user_name TEXT)"
        );
        jdbc.execute(
                "CREATE TABLE chat_message ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "session_id TEXT NOT NULL,"
                        + "role TEXT NOT NULL,"
                        + "content TEXT NOT NULL,"
                        + "timestamp TEXT NOT NULL)"
        );
        jdbc.execute(
                "CREATE TABLE chat_message_recall ("
                        + "message_id INTEGER PRIMARY KEY,"
                        + "payload_json TEXT NOT NULL)"
        );
        jdbc.execute(
                "CREATE TABLE chat_session_rag_state ("
                        + "session_id TEXT PRIMARY KEY,"
                        + "last_refined_at INTEGER NOT NULL,"
                        + "last_message_at_seen INTEGER NOT NULL,"
                        + "last_chunk_id TEXT,"
                        + "last_error TEXT,"
                        + "retry_count INTEGER NOT NULL DEFAULT 0)"
        );
        repo = new SqliteSessionRepo(jdbc, new com.example.agentweb.domain.auth.CurrentUserProvider(() -> java.util.Optional.empty()));
    }

    /** below-threshold 哨兵, 与 SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD 一致, 供 SQL 排除。 */
    private static final String BELOW = "score below threshold";

    /** 直插一行 rag_state, 模拟某 session 上次评分结果。 */
    private void insertRagState(String sessionId, Instant lastMessageAtSeen,
                                String lastError, int retryCount) {
        jdbc.update(
                "INSERT INTO chat_session_rag_state "
                        + "(session_id, last_refined_at, last_message_at_seen, last_chunk_id, last_error, retry_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, 0L, lastMessageAtSeen.toEpochMilli(), null, lastError, retryCount);
    }

    @Test
    public void saveSession_then_findById_should_round_trip_aggregate_fields() {
        ChatSession session = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        session.setTitle("review patch");
        session.setEnv("test");
        session.setResumeId("resume-abc");
        session.setClientIp("9.9.9.9");
        session.setUserId("user-42");
        session.setUserName("周锐");

        repo.saveSession(session);

        ChatSession loaded = repo.findById("sess-1");
        assertNotNull(loaded);
        assertEquals("sess-1", loaded.getId());
        assertEquals(AgentType.CLAUDE, loaded.getAgentType());
        assertEquals("/tmp/work", loaded.getWorkingDir());
        assertEquals("review patch", loaded.getTitle());
        assertEquals("test", loaded.getEnv());
        assertEquals("resume-abc", loaded.getResumeId());
        assertEquals("9.9.9.9", loaded.getClientIp());
        // 回归: user_id 写进库后必须能随聚合根读回 (此前 SESSION_COLUMNS/mapSession 漏读, 恒为 null)
        assertEquals("user-42", loaded.getUserId());
        // user_name(创建者姓名, 审计用)同样需随聚合根读回
        assertEquals("周锐", loaded.getUserName());
        assertNull(loaded.getFeedback());
        assertTrue(loaded.getMessages().isEmpty());
    }

    @Test
    public void saveSession_duplicate_primary_key_should_be_swallowed_by_insert_or_ignore_without_throwing_or_overwriting() {
        ChatSession first = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        first.setTitle("original");
        repo.saveSession(first);

        ChatSession duplicate = newSession("sess-1", "/other/dir", AgentType.CODEX);
        duplicate.setTitle("overridden");
        repo.saveSession(duplicate);

        ChatSession loaded = repo.findById("sess-1");
        assertEquals("original", loaded.getTitle());
        assertEquals("/tmp/work", loaded.getWorkingDir());
        assertEquals(AgentType.CLAUDE, loaded.getAgentType());
    }

    @Test
    public void addMessage_then_findById_should_round_trip_messages_by_id_order() {
        ChatSession session = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);

        repo.addMessage("sess-1", new com.example.agentweb.domain.chat.ChatMessage(
                "user", "hello", Instant.parse("2026-05-25T10:00:00Z")));
        repo.addMessage("sess-1", new com.example.agentweb.domain.chat.ChatMessage(
                "assistant", "hi there", Instant.parse("2026-05-25T10:00:01Z")));

        ChatSession loaded = repo.findById("sess-1");
        assertEquals(2, loaded.getMessages().size());
        assertEquals("user", loaded.getMessages().get(0).getRole());
        assertEquals("hello", loaded.getMessages().get(0).getContent());
        assertEquals("assistant", loaded.getMessages().get(1).getRole());
        assertEquals("hi there", loaded.getMessages().get(1).getContent());
    }

    @Test
    public void updateResumeId_should_be_idempotent_and_allow_null() {
        ChatSession session = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        session.setResumeId("old-resume");
        repo.saveSession(session);

        repo.updateResumeId("sess-1", "new-resume");
        assertEquals("new-resume", repo.findById("sess-1").getResumeId());

        // 再次写入相同值, 不应抛错且结果一致 —— 幂等
        repo.updateResumeId("sess-1", "new-resume");
        assertEquals("new-resume", repo.findById("sess-1").getResumeId());

        repo.updateResumeId("sess-1", null);
        assertNull(repo.findById("sess-1").getResumeId());
    }

    /**
     * 自 RewindFeatureTest 下沉: 验证 addMessage 写入后, findById 回放的消息持有非空且单调递增的 id。
     */
    @Test
    public void loadMessages_should_populate_id() {
        ChatSession session = newSession("sess-load-id", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);
        repo.addMessage("sess-load-id", new com.example.agentweb.domain.chat.ChatMessage(
                "user", "msg1", Instant.parse("2026-05-25T10:00:00Z")));
        repo.addMessage("sess-load-id", new com.example.agentweb.domain.chat.ChatMessage(
                "assistant", "ack1", Instant.parse("2026-05-25T10:00:01Z")));

        List<com.example.agentweb.domain.chat.ChatMessage> loaded =
                repo.findById("sess-load-id").getMessages();
        assertEquals(2, loaded.size());
        assertNotNull(loaded.get(0).getId(), "第一条消息应有 id");
        assertNotNull(loaded.get(1).getId(), "第二条消息应有 id");
        assertTrue(loaded.get(1).getId() > loaded.get(0).getId(), "id 应单调递增");
    }

    /**
     * 自 RewindFeatureTest 下沉: 截断点 id 不存在时应返回 0 且不影响现存消息。
     */
    @Test
    public void truncateFrom_nonexistent_id_should_delete_zero() {
        ChatSession session = newSession("sess-truncate-noop", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);
        repo.addMessage("sess-truncate-noop", new com.example.agentweb.domain.chat.ChatMessage(
                "user", "only", Instant.parse("2026-05-25T10:00:00Z")));

        int deleted = repo.truncateFrom("sess-truncate-noop", 999999999L);

        assertEquals(0, deleted);
        assertEquals(1, repo.findById("sess-truncate-noop").getMessages().size());
    }

    @Test
    public void truncateFrom_should_delete_messages_and_clear_resume_id() {
        ChatSession session = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        session.setResumeId("resume-x");
        repo.saveSession(session);
        repo.addMessage("sess-1", new com.example.agentweb.domain.chat.ChatMessage(
                "user", "m1", Instant.parse("2026-05-25T10:00:00Z")));
        repo.addMessage("sess-1", new com.example.agentweb.domain.chat.ChatMessage(
                "assistant", "m2", Instant.parse("2026-05-25T10:00:01Z")));
        repo.addMessage("sess-1", new com.example.agentweb.domain.chat.ChatMessage(
                "user", "m3", Instant.parse("2026-05-25T10:00:02Z")));

        long secondMsgId = jdbc.queryForObject(
                "SELECT id FROM chat_message WHERE session_id = ? ORDER BY id ASC LIMIT 1 OFFSET 1",
                Long.class, "sess-1");

        int deleted = repo.truncateFrom("sess-1", secondMsgId);

        assertEquals(2, deleted);
        ChatSession loaded = repo.findById("sess-1");
        assertEquals(1, loaded.getMessages().size());
        assertEquals("m1", loaded.getMessages().get(0).getContent());
        assertNull(loaded.getResumeId());
    }

    @Test
    public void setShareToken_first_write_returns_new_token_repeat_call_returns_old_token() {
        ChatSession session = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);

        String first = repo.setShareToken("sess-1", "share-aaa");
        assertEquals("share-aaa", first);

        // 已有 token, 再次调用应返回旧值而非覆盖 —— 分享链接稳定性保障
        String second = repo.setShareToken("sess-1", "share-bbb");
        assertEquals("share-aaa", second);

        ChatSession byToken = repo.findByShareToken("share-aaa");
        assertNotNull(byToken);
        assertEquals("sess-1", byToken.getId());
        assertNull(repo.findByShareToken("share-bbb"));
    }

    @Test
    public void saveFeedback_should_persist_rating_and_be_read_by_findById() {
        ChatSession session = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);

        Instant ratedAt = Instant.parse("2026-05-25T10:30:00Z");
        Feedback feedback = new Feedback(FeedbackRating.PARTIALLY_CORRECT, "缺少根因", ratedAt);
        repo.saveFeedback("sess-1", feedback);

        ChatSession loaded = repo.findById("sess-1");
        Feedback stored = loaded.getFeedback();
        assertNotNull(stored);
        assertEquals(FeedbackRating.PARTIALLY_CORRECT, stored.getRating());
        assertEquals("缺少根因", stored.getComment());
        assertEquals(ratedAt.toEpochMilli(), stored.getUpdatedAt().toEpochMilli());
    }

    @Test
    public void deleteById_should_cascade_delete_messages() {
        ChatSession session = newSession("sess-1", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);
        repo.addMessage("sess-1", new com.example.agentweb.domain.chat.ChatMessage(
                "user", "bye", Instant.parse("2026-05-25T10:00:00Z")));

        repo.deleteById("sess-1");

        assertNull(repo.findById("sess-1"));
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ?",
                Integer.class, "sess-1");
        assertEquals(0, remaining);
    }

    @Test
    public void saveSession_should_initialize_last_message_at_to_created_at() {
        ChatSession session = newSession("sess-init-lma", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);

        Long lastMessageAt = jdbc.queryForObject(
                "SELECT last_message_at FROM chat_session WHERE id = ?",
                Long.class, "sess-init-lma");

        assertNotNull(lastMessageAt, "saveSession 之后 last_message_at 不能为空");
        // newSession 用的固定 createdAt = 2026-05-25T09:00:00Z
        assertEquals(Instant.parse("2026-05-25T09:00:00Z").toEpochMilli(), lastMessageAt.longValue());
    }

    @Test
    public void addMessage_should_bump_last_message_at_to_message_timestamp() {
        ChatSession session = newSession("sess-bump-lma", "/tmp/work", AgentType.CLAUDE);
        repo.saveSession(session);

        Instant msgAt = Instant.parse("2026-05-25T12:34:56Z");
        repo.addMessage("sess-bump-lma", new com.example.agentweb.domain.chat.ChatMessage(
                "user", "hi", msgAt));

        Long lastMessageAt = jdbc.queryForObject(
                "SELECT last_message_at FROM chat_session WHERE id = ?",
                Long.class, "sess-bump-lma");

        assertEquals(msgAt.toEpochMilli(), lastMessageAt.longValue());
    }

    @Test
    public void findIdsWithLastMessageBefore_should_filter_and_return_in_ascending_time_order() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        ChatSession older = new ChatSession("sess-old", AgentType.CLAUDE,
                "/w", t.minusSeconds(3600), null);
        ChatSession newer = new ChatSession("sess-new", AgentType.CLAUDE,
                "/w", t.minusSeconds(600), null);
        ChatSession future = new ChatSession("sess-future", AgentType.CLAUDE,
                "/w", t.plusSeconds(60), null);
        repo.saveSession(older);
        repo.saveSession(newer);
        repo.saveSession(future);

        List<String> ids = repo.findIdsWithLastMessageBefore(t.toEpochMilli(), BELOW, 3, 10);

        assertEquals(2, ids.size());
        assertEquals("sess-old", ids.get(0));
        assertEquals("sess-new", ids.get(1));
    }

    @Test
    public void findIdsWithLastMessageBefore_should_apply_limit() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            ChatSession s = new ChatSession("sess-" + i, AgentType.CLAUDE,
                    "/w", t.minusSeconds(3600L - i), null);
            repo.saveSession(s);
        }

        List<String> ids = repo.findIdsWithLastMessageBefore(t.toEpochMilli(), BELOW, 3, 3);

        assertEquals(3, ids.size());
    }

    @Test
    public void findIdsWithLastMessageBefore_should_exclude_sessions_with_null_last_message_at() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        jdbc.update(
                "INSERT INTO chat_session (id, agent_type, working_dir, created_at, last_message_at) "
                        + "VALUES (?, ?, ?, ?, NULL)",
                "sess-null-lma", "CLAUDE", "/w", t.toString());

        List<String> ids = repo.findIdsWithLastMessageBefore(
                t.plusSeconds(99999).toEpochMilli(), BELOW, 3, 10);

        assertTrue(ids.isEmpty());
    }

    @Test
    public void findIdsWithLastMessageBefore_should_exclude_successful_sessions_without_new_messages() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        Instant lma = t.minusSeconds(600);
        ChatSession s = new ChatSession("sess-done", AgentType.CLAUDE, "/w", lma, null);
        repo.saveSession(s);
        // 成功(last_error=null)且 last_message_at_seen == last_message_at
        insertRagState("sess-done", lma, null, 0);

        List<String> ids = repo.findIdsWithLastMessageBefore(t.toEpochMilli(), BELOW, 3, 10);

        assertTrue(ids.isEmpty(), "已成功且无新消息应被排除");
    }

    @Test
    public void findIdsWithLastMessageBefore_should_exclude_below_threshold_sessions() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        Instant lma = t.minusSeconds(600);
        repo.saveSession(new ChatSession("sess-bt", AgentType.CLAUDE, "/w", lma, null));
        insertRagState("sess-bt", lma, BELOW, 0);

        List<String> ids = repo.findIdsWithLastMessageBefore(t.toEpochMilli(), BELOW, 3, 10);

        assertTrue(ids.isEmpty(), "below-threshold 不重试, 应被排除");
    }

    @Test
    public void findIdsWithLastMessageBefore_real_failure_under_retry_cap_should_be_picked_for_retry() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        Instant lma = t.minusSeconds(600);
        repo.saveSession(new ChatSession("sess-err", AgentType.CLAUDE, "/w", lma, null));
        insertRagState("sess-err", lma, "boom", 1);  // retry_count=1 < 3

        List<String> ids = repo.findIdsWithLastMessageBefore(t.toEpochMilli(), BELOW, 3, 10);

        assertEquals(java.util.Collections.singletonList("sess-err"), ids);
    }

    @Test
    public void findIdsWithLastMessageBefore_real_failure_at_retry_cap_should_be_excluded() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        Instant lma = t.minusSeconds(600);
        repo.saveSession(new ChatSession("sess-err", AgentType.CLAUDE, "/w", lma, null));
        insertRagState("sess-err", lma, "boom", 3);  // retry_count=3 >= 3

        List<String> ids = repo.findIdsWithLastMessageBefore(t.toEpochMilli(), BELOW, 3, 10);

        assertTrue(ids.isEmpty(), "真·失败达重试上限应被排除");
    }

    @Test
    public void findIdsWithLastMessageBefore_successful_but_with_new_messages_should_be_picked_for_rescore() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        Instant lma = t.minusSeconds(600);
        ChatSession s = new ChatSession("sess-bumped", AgentType.CLAUDE, "/w", lma, null);
        repo.saveSession(s);
        // state 停留在更早的消息时间, 而当前 last_message_at = lma (saveSession 初始化为 createdAt) → 不等 → 有新消息
        insertRagState("sess-bumped", lma.minusSeconds(300), null, 0);

        List<String> ids = repo.findIdsWithLastMessageBefore(t.toEpochMilli(), BELOW, 3, 10);

        assertEquals(java.util.Collections.singletonList("sess-bumped"), ids);
    }

    @Test
    public void findIdsWithLastMessageBefore_old_sessions_should_not_block_window_new_sessions_still_picked() {
        // 回归原缺陷: 队头 5 个已成功旧会话 + 队尾 1 个未评新会话, LIMIT 5。
        // 旧 SQL 会取最早 5 个 (全是已处理), 新会话取不到; 修复后旧会话被 JOIN 排除, 新会话入选。
        Instant base = Instant.parse("2026-05-25T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            Instant lma = base.plusSeconds(i);          // 队头, 时间最早
            String id = "old-" + i;
            repo.saveSession(new ChatSession(id, AgentType.CLAUDE, "/w", lma, null));
            insertRagState(id, lma, null, 0);           // 已成功
        }
        Instant newLma = base.plusSeconds(1000);        // 队尾, 时间最晚
        repo.saveSession(new ChatSession("fresh", AgentType.CLAUDE, "/w", newLma, null));

        List<String> ids = repo.findIdsWithLastMessageBefore(
                base.plusSeconds(99999).toEpochMilli(), BELOW, 3, 5);

        assertEquals(java.util.Collections.singletonList("fresh"), ids,
                "已处理旧会话被排除, 新会话不再被窗口饿死");
    }

    @Test
    public void findIdsWithLastMessageBefore_second_precision_session_vs_millis_precision_state_should_not_misjudge_as_new_messages() {
        // 回归精度 bug: 存量 chat_session.last_message_at 为秒精度 (...000),
        // rag_state.last_message_at_seen 为毫秒精度 (...284)。两者同源同一条消息,
        // 直接 <> 比较会误判"有新消息"导致已成功会话永远入选钉死窗口。SQL 需 /1000 秒级归一。
        Instant secPrecision = Instant.ofEpochMilli(1779617687000L);   // 秒精度 (毫秒部分=000)
        Instant msPrecision = Instant.ofEpochMilli(1779617687284L);    // 同秒, 带 284 毫秒
        // 直接写秒精度的 last_message_at (绕过 saveSession, 模拟存量数据)
        jdbc.update(
                "INSERT INTO chat_session (id, agent_type, working_dir, created_at, last_message_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "sess-prec", "CLAUDE", "/w", "2026-05-24T10:14:47Z", secPrecision.toEpochMilli());
        insertRagState("sess-prec", msPrecision, null, 0);  // 成功, 但 state 是毫秒精度

        List<String> ids = repo.findIdsWithLastMessageBefore(
                secPrecision.plusSeconds(99999).toEpochMilli(), BELOW, 3, 10);

        assertTrue(ids.isEmpty(),
                "秒精度 session 与毫秒精度 state 源自同一消息, 应判为无新消息而排除, 不得误判入选");
    }

    @Test
    public void findIdsWithLastMessageAfter_should_include_boundary_equal_ascending_order_excluding_earlier_sessions() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        repo.saveSession(new ChatSession("sess-old", AgentType.CLAUDE, "/w", t.minusSeconds(3600), null));
        repo.saveSession(new ChatSession("sess-at", AgentType.CLAUDE, "/w", t, null));
        repo.saveSession(new ChatSession("sess-future", AgentType.CLAUDE, "/w", t.plusSeconds(60), null));
        repo.saveSession(new ChatSession("sess-new", AgentType.CLAUDE, "/w", t.plusSeconds(600), null));

        List<String> ids = repo.findIdsWithLastMessageAfter(t.toEpochMilli());

        // 边界 == 入选; 升序; t-3600 的会话被排除
        assertEquals(java.util.Arrays.asList("sess-at", "sess-future", "sess-new"), ids);
    }

    @Test
    public void findIdsWithLastMessageAfter_should_exclude_sessions_with_null_last_message_at() {
        Instant t = Instant.parse("2026-05-25T10:00:00Z");
        jdbc.update(
                "INSERT INTO chat_session (id, agent_type, working_dir, created_at, last_message_at) "
                        + "VALUES (?, ?, ?, ?, NULL)",
                "sess-null-lma", "CLAUDE", "/w", t.toString());

        List<String> ids = repo.findIdsWithLastMessageAfter(t.minusSeconds(99999).toEpochMilli());

        assertTrue(ids.isEmpty());
    }

    @Test
    public void addMessageReturningId_should_return_autoincrement_id_monotonically_increasing() {
        ChatSession session = newSession("sess-rid", "/w", AgentType.CLAUDE);
        repo.saveSession(session);

        long id1 = repo.addMessageReturningId("sess-rid",
                new com.example.agentweb.domain.chat.ChatMessage("user", "m1", Instant.parse("2026-05-25T10:00:00Z")));
        long id2 = repo.addMessageReturningId("sess-rid",
                new com.example.agentweb.domain.chat.ChatMessage("assistant", "m2", Instant.parse("2026-05-25T10:00:01Z")));

        assertTrue(id1 > 0, "应返回有效自增 id");
        assertTrue(id2 > id1, "id 应单调递增");
        // 同时仍写入 last_message_at
        Long lma = jdbc.queryForObject("SELECT last_message_at FROM chat_session WHERE id = ?", Long.class, "sess-rid");
        assertEquals(Instant.parse("2026-05-25T10:00:01Z").toEpochMilli(), lma.longValue());
    }

    @Test
    public void saveRecall_should_persist_per_message() {
        repo.saveSession(newSession("s-A", "/w", AgentType.CLAUDE));
        repo.saveSession(newSession("s-B", "/w", AgentType.CLAUDE));
        long aMsg = repo.addMessageReturningId("s-A",
                new com.example.agentweb.domain.chat.ChatMessage("assistant", "ans", Instant.parse("2026-05-25T10:00:00Z")));
        long bMsg = repo.addMessageReturningId("s-B",
                new com.example.agentweb.domain.chat.ChatMessage("assistant", "ans", Instant.parse("2026-05-25T10:00:00Z")));

        repo.saveRecall(aMsg, "{\"query\":\"q\",\"hits\":[]}");
        repo.saveRecall(bMsg, "{\"query\":\"other\",\"hits\":[]}");

        assertEquals("{\"query\":\"q\",\"hits\":[]}", jdbc.queryForObject(
                "SELECT payload_json FROM chat_message_recall WHERE message_id = ?", String.class, aMsg));
        assertEquals("{\"query\":\"other\",\"hits\":[]}", jdbc.queryForObject(
                "SELECT payload_json FROM chat_message_recall WHERE message_id = ?", String.class, bMsg));
    }

    @Test
    public void saveRecall_duplicate_message_id_should_overwrite() {
        repo.saveSession(newSession("s-A", "/w", AgentType.CLAUDE));
        long msg = repo.addMessageReturningId("s-A",
                new com.example.agentweb.domain.chat.ChatMessage("assistant", "ans", Instant.parse("2026-05-25T10:00:00Z")));

        repo.saveRecall(msg, "v1");
        repo.saveRecall(msg, "v2");

        assertEquals("v2", jdbc.queryForObject(
                "SELECT payload_json FROM chat_message_recall WHERE message_id = ?", String.class, msg));
    }

    @Test
    public void deleteById_should_cascade_delete_recall_details() {
        repo.saveSession(newSession("s-A", "/w", AgentType.CLAUDE));
        long msg = repo.addMessageReturningId("s-A",
                new com.example.agentweb.domain.chat.ChatMessage("assistant", "ans", Instant.parse("2026-05-25T10:00:00Z")));
        repo.saveRecall(msg, "{\"hits\":[]}");

        repo.deleteById("s-A");

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message_recall WHERE message_id = ?", Integer.class, msg);
        assertEquals(0, remaining);
    }

    @Test
    public void truncateFrom_should_remove_recall_details_for_truncated_messages() {
        repo.saveSession(newSession("s-A", "/w", AgentType.CLAUDE));
        long keep = repo.addMessageReturningId("s-A",
                new com.example.agentweb.domain.chat.ChatMessage("user", "u1", Instant.parse("2026-05-25T10:00:00Z")));
        long cut = repo.addMessageReturningId("s-A",
                new com.example.agentweb.domain.chat.ChatMessage("assistant", "a1", Instant.parse("2026-05-25T10:00:01Z")));
        repo.saveRecall(cut, "{\"hits\":[]}");

        repo.truncateFrom("s-A", cut);

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message_recall WHERE message_id = ?", Integer.class, cut);
        assertEquals(0, remaining, "被截断消息的召回应清掉");
        // keep 这条消息不受影响
        assertNotNull(jdbc.queryForObject("SELECT id FROM chat_message WHERE id = ?", Long.class, keep));
    }

    private ChatSession newSession(String id, String workingDir, AgentType type) {
        return new ChatSession(id, type, workingDir,
                Instant.parse("2026-05-25T09:00:00Z"), null);
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
