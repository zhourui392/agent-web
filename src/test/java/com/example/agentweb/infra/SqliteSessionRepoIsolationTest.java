package com.example.agentweb.infra;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话数据隔离专项测试，覆盖 {@link CurrentUserProvider} 的两种态：
 * 本人可见+老数据可见+他人不可见(findById null) / 无上下文(后台)全见。
 *
 * <p>用可控的 {@link StubUserContext} 喂真实 {@link CurrentUserProvider}，
 * 直接驱动 {@code currentUserId}/{@code shouldFilter}，不依赖真实 HTTP/登录会话 上下文。</p>
 *
 * <p>管理员跨用户视野已迁到 {@code /admin/*} 独立路径（{@code AdminAuthFilter} 口令 +
 * {@code ConversationQueryService} 全量投影），不再走 {@link CurrentUserProvider}。</p>
 *
 * @author zhourui(V33215020)
 */
public class SqliteSessionRepoIsolationTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private StubUserContext userContext;
    private CurrentUserProvider provider;
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
        File dbFile = tempDir.resolve("iso-test.db").toFile();
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
        userContext = new StubUserContext();
        provider = new CurrentUserProvider(userContext);
        repo = new SqliteSessionRepo(jdbc, provider);
        query = new SqliteChatSessionQueryService(jdbc, provider);

        // 预置三条会话：alice 的、bob 的、老数据(NULL)
        saveAs("alice", "sess-alice");
        saveAs("bob", "sess-bob");
        saveAs(null, "sess-legacy");
    }

    private void saveAs(String userId, String sessionId) {
        userContext.userId = userId;
        ChatSession s = new ChatSession(sessionId, AgentType.CLAUDE, "/tmp/wd", Instant.now(), new ArrayList<>());
        s.setUserId(userId);
        repo.saveSession(s);
    }

    private List<String> summaryIds() {
        return query.findSummaryPaged(0, 100).stream()
                .map(com.example.agentweb.app.ChatSessionSummary::getSessionId)
                .collect(Collectors.toList());
    }

    @Test
    public void normalUser_sees_own_and_legacy_only() {
        userContext.userId = "alice";
        List<String> ids = summaryIds();
        assertTrue(ids.contains("sess-alice"), "本人会话可见");
        assertTrue(ids.contains("sess-legacy"), "老数据(NULL)可见");
        assertTrue(!ids.contains("sess-bob"), "他人会话不可见");
    }

    @Test
    public void normalUser_findById_other_returns_null() {
        userContext.userId = "alice";
        assertNotNull(repo.findById("sess-alice"), "本人 findById 可读");
        assertNotNull(repo.findById("sess-legacy"), "老数据 findById 可读");
        assertNull(repo.findById("sess-bob"), "他人 findById 应为 null(隔离)");
    }

    @Test
    public void noContext_background_sees_all() {
        userContext.userId = null;
        List<String> ids = summaryIds();
        assertTrue(ids.contains("sess-alice") && ids.contains("sess-bob") && ids.contains("sess-legacy"),
                "无上下文(后台)应不过滤，看全部");
        assertNotNull(repo.findById("sess-bob"), "后台 findById 不被隔离误伤");
    }

    /** 隔离总开关关闭：即便有当前用户(alice)，也能看到他人(bob)与全部会话。 */
    @Test
    public void isolation_disabled_sees_all_users() {
        userContext.userId = "alice";
        CurrentUserProvider disabled = new CurrentUserProvider(userContext, false);
        SqliteSessionRepo openRepo = new SqliteSessionRepo(jdbc, disabled);
        SqliteChatSessionQueryService openQuery = new SqliteChatSessionQueryService(jdbc, disabled);

        List<String> ids = openQuery.findSummaryPaged(0, 100).stream()
                .map(com.example.agentweb.app.ChatSessionSummary::getSessionId)
                .collect(Collectors.toList());
        assertTrue(ids.contains("sess-alice") && ids.contains("sess-bob") && ids.contains("sess-legacy"),
                "隔离关闭应不过滤，看全部");
        assertNotNull(openRepo.findById("sess-bob"), "隔离关闭时 findById 可读他人会话");
    }
}
