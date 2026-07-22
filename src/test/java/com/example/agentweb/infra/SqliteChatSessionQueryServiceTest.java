package com.example.agentweb.infra;

import com.example.agentweb.app.ChatMessageView;
import com.example.agentweb.app.ChatSessionSummary;
import com.example.agentweb.app.SharedSessionView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteChatSessionQueryService} 轻量集成测试：摘要投影、消息+召回回放、
 * 分享视图、用户隔离口径。真实 SQLite,不起 Spring 容器。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public class SqliteChatSessionQueryServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private StubUserContext userContext;
    private SqliteSessionRepo repo;
    private SqliteChatSessionQueryService query;

    /** 可控当前用户：userId 决定身份；null 表示后台/无上下文。 */
    private static final class StubUserContext implements UserContext {
        private String userId;

        @Override
        public Optional<LoginUser> currentUser() {
            return userId == null ? Optional.empty() : Optional.of(new LoginUser(userId, userId, null));
        }
    }

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("query-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chat_session ("
                + "id TEXT PRIMARY KEY, agent_type TEXT NOT NULL, working_dir TEXT NOT NULL, "
                + "created_at TEXT NOT NULL, resume_id TEXT, share_token TEXT, env TEXT, title TEXT, "
                + "feedback_rating TEXT, feedback_comment TEXT, feedback_at TEXT, "
                + "last_message_at INTEGER, client_ip TEXT, user_id TEXT, user_name TEXT)");
        jdbc.execute("CREATE TABLE chat_message ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, "
                + "role TEXT NOT NULL, content TEXT NOT NULL, timestamp TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE chat_message_recall ("
                + "message_id INTEGER PRIMARY KEY, payload_json TEXT NOT NULL)");
        userContext = new StubUserContext();
        CurrentUserProvider provider = new CurrentUserProvider(userContext);
        repo = new SqliteSessionRepo(jdbc, provider);
        query = new SqliteChatSessionQueryService(jdbc, provider);
    }

    private ChatSession newSession(String id, Instant createdAt) {
        return new ChatSession(id, AgentType.CLAUDE, "/tmp/wd", createdAt, new ArrayList<>());
    }

    @Test
    public void findSummaryPaged_should_order_by_created_desc_and_truncate_overlong_title() {
        ChatSession s1 = newSession("sess-1", Instant.parse("2026-05-25T10:00:00Z"));
        ChatSession s2 = new ChatSession("sess-2", AgentType.CODEX, "/tmp/b",
                Instant.parse("2026-05-25T11:00:00Z"), new ArrayList<>());
        s2.setTitle(repeat('x', 80));
        s2.setUserId("alice");
        userContext.userId = "alice";
        repo.saveSession(s1);
        repo.saveSession(s2);
        repo.addMessage("sess-1", new ChatMessage(
                "user", "first message", Instant.parse("2026-05-25T10:00:01Z")));

        List<ChatSessionSummary> summaries = query.findSummaryPaged(0, 10);

        assertEquals(2, summaries.size());
        assertEquals("sess-2", summaries.get(0).getSessionId());
        String renderedTitle = summaries.get(0).getTitle();
        assertEquals(53, renderedTitle.length());
        assertTrue(renderedTitle.endsWith("..."));
        // 摘要投影带出归属(创建者), 供前端按归属隐藏删除按钮
        assertEquals("alice", summaries.get(0).getUserId());
        assertEquals("sess-1", summaries.get(1).getSessionId());
        // sess-1 无显式 title, 回退到首条 user 消息内容
        assertEquals("first message", summaries.get(1).getTitle());
        assertEquals(1, summaries.get(1).getMessageCount());
        // sess-1 无归属(老数据/公共会话) → userId 为 null
        assertNull(summaries.get(1).getUserId());
    }

    @Test
    public void findMessageViews_should_join_recall_payload_per_message() {
        repo.saveSession(newSession("sess-1", Instant.parse("2026-05-25T10:00:00Z")));
        long userMsg = repo.addMessageReturningId("sess-1",
                new ChatMessage("user", "q", Instant.parse("2026-05-25T10:01:00Z")));
        long assistantMsg = repo.addMessageReturningId("sess-1",
                new ChatMessage("assistant", "a", Instant.parse("2026-05-25T10:02:00Z")));
        repo.saveRecall(assistantMsg, "{\"query\":\"q\",\"hits\":[]}");

        List<ChatMessageView> views = query.findMessageViews("sess-1");

        assertEquals(2, views.size());
        assertEquals(userMsg, views.get(0).getId());
        assertEquals("user", views.get(0).getRole());
        assertNull(views.get(0).getRecall());
        assertEquals("a", views.get(1).getContent());
        assertEquals("{\"query\":\"q\",\"hits\":[]}", views.get(1).getRecall());
    }

    @Test
    public void findMessageViews_should_return_null_for_unknown_session() {
        assertNull(query.findMessageViews("ghost"));
    }

    @Test
    public void findMessageViews_should_hide_other_users_session_when_isolated() {
        userContext.userId = "bob";
        ChatSession bobSession = newSession("sess-bob", Instant.parse("2026-05-25T10:00:00Z"));
        bobSession.setUserId("bob");
        repo.saveSession(bobSession);

        userContext.userId = "alice";
        assertNull(query.findMessageViews("sess-bob"), "隔离开启时他人会话不可见");

        userContext.userId = null;
        assertEquals(0, query.findMessageViews("sess-bob").size(), "后台无上下文不过滤");
    }

    @Test
    public void findSharedView_should_return_meta_and_messages_by_token() {
        ChatSession session = newSession("sess-1", Instant.parse("2026-05-26T08:00:00Z"));
        session.setTitle("debug session");
        repo.saveSession(session);
        jdbc.update("UPDATE chat_session SET title = ?, share_token = ? WHERE id = ?",
                "debug session", "tok-abc", "sess-1");
        long msg = repo.addMessageReturningId("sess-1",
                new ChatMessage("assistant", "ans", Instant.parse("2026-05-26T08:01:00Z")));
        repo.saveRecall(msg, "{\"hits\":[]}");

        SharedSessionView view = query.findSharedView("tok-abc");

        assertEquals("debug session", view.getTitle());
        assertEquals("CLAUDE", view.getAgentType());
        assertEquals("2026-05-26T08:00:00Z", view.getCreatedAt());
        assertEquals(1, view.getMessages().size());
        assertEquals("{\"hits\":[]}", view.getMessages().get(0).getRecall());
    }

    @Test
    public void findSharedView_should_return_null_for_unknown_token() {
        assertNull(query.findSharedView("nope"));
    }

    @Test
    public void isSharedImageReferenced_should_require_validTokenAndExactMessageReference() {
        ChatSession session = newSession("sess-1", Instant.parse("2026-05-26T08:00:00Z"));
        repo.saveSession(session);
        jdbc.update("UPDATE chat_session SET share_token = ? WHERE id = ?", "tok-img", "sess-1");
        repo.addMessage("sess-1", new ChatMessage("user",
                "question\n/tmp/wd/upload_pic/s/a.png", Instant.parse("2026-05-26T08:01:00Z")));

        assertTrue(query.isSharedImageReferenced("tok-img", "/tmp/wd/upload_pic/s/a.png"));
        org.junit.jupiter.api.Assertions.assertFalse(
                query.isSharedImageReferenced("tok-img", "/tmp/wd/secret.png"));
        org.junit.jupiter.api.Assertions.assertFalse(
                query.isSharedImageReferenced("wrong-token", "/tmp/wd/upload_pic/s/a.png"));
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
