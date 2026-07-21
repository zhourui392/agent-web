package com.example.agentweb.infra;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.schedule.ScheduledTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时任务按用户隔离专项测试，与 {@code SqliteSessionRepoIsolationTest} 同语义：
 * 本人可见 + 老数据(NULL)可见 + 他人不可见(findById/delete 隔离) / 无上下文(后台调度器)全见。
 *
 * <p>用可控 {@link StubUserContext} 喂真实 {@link CurrentUserProvider} 驱动
 * {@code currentUserId}/{@code shouldFilter}, 不依赖真实 HTTP/登录会话。</p>
 *
 * @author zhourui(V33215020)
 */
public class SqliteScheduledTaskRepoIsolationTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private StubUserContext userContext;
    private SqliteScheduledTaskRepo repo;

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
        File dbFile = tempDir.resolve("sched-iso-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE scheduled_task ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, cron_expr TEXT NOT NULL, "
                + "prompt TEXT NOT NULL, working_dir TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, "
                + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_run_at TEXT, "
                + "last_session_id TEXT, user_id TEXT)");
        userContext = new StubUserContext();
        repo = new SqliteScheduledTaskRepo(jdbc, new CurrentUserProvider(userContext));

        // 预置三条任务：alice 的、bob 的、老数据(NULL)
        saveAs("alice", "task-alice");
        saveAs("bob", "task-bob");
        saveAs(null, "task-legacy");
    }

    private void saveAs(String userId, String name) {
        ScheduledTask t = new ScheduledTask(name, "0 0 1 * * ?", "p", "/tmp/wd");
        t.setUserId(userId);
        repo.save(t);
    }

    private List<String> findAllNames() {
        return repo.findAll().stream().map(ScheduledTask::getName).collect(Collectors.toList());
    }

    private String idOf(String name) {
        return jdbc.queryForObject("SELECT id FROM scheduled_task WHERE name = ?", String.class, name);
    }

    @Test
    public void normalUser_findAll_sees_own_and_legacy_only() {
        userContext.userId = "alice";
        List<String> names = findAllNames();
        assertTrue(names.contains("task-alice"), "本人任务可见");
        assertTrue(names.contains("task-legacy"), "老数据(NULL)可见");
        assertTrue(!names.contains("task-bob"), "他人任务不可见");
    }

    @Test
    public void normalUser_findById_other_returns_null() {
        userContext.userId = "alice";
        assertNotNull(repo.findById(idOf("task-alice")), "本人 findById 可读");
        assertNotNull(repo.findById(idOf("task-legacy")), "老数据 findById 可读");
        assertNull(repo.findById(idOf("task-bob")), "他人 findById 应为 null(隔离)");
    }

    @Test
    public void normalUser_delete_other_is_noop() {
        String bobId = idOf("task-bob");
        userContext.userId = "alice";
        repo.deleteById(bobId);
        // 仍在库里(用后台视角确认)
        userContext.userId = null;
        assertNotNull(repo.findById(bobId), "他人任务不应被删除");
    }

    @Test
    public void noContext_background_sees_all() {
        userContext.userId = null;
        List<String> names = findAllNames();
        assertTrue(names.contains("task-alice") && names.contains("task-bob") && names.contains("task-legacy"),
                "无上下文(后台)应不过滤，看全部");
        assertNotNull(repo.findById(idOf("task-bob")), "后台 findById 不被隔离误伤");
    }

    @Test
    public void findAllEnabled_is_never_user_scoped() {
        // 调度器加载: 即使带某用户身份, 也必须覆盖所有用户的启用任务
        userContext.userId = "alice";
        List<String> names = repo.findAllEnabled().stream()
                .map(ScheduledTask::getName).collect(Collectors.toList());
        assertEquals(3, names.size(), "findAllEnabled 不按用户隔离");
        assertTrue(names.contains("task-bob"), "他人启用任务也必须被调度器加载");
    }
}
