package com.example.agentweb.infra;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.schedule.ScheduledTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-25
 */
public class SqliteScheduledTaskRepoTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource ds;
    private JdbcTemplate jdbc;
    private SqliteScheduledTaskRepo repo;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("scheduled-task-test.db").toFile();
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE scheduled_task ("
                        + "id TEXT PRIMARY KEY,"
                        + "name TEXT NOT NULL,"
                        + "cron_expr TEXT NOT NULL,"
                        + "prompt TEXT NOT NULL,"
                        + "working_dir TEXT NOT NULL,"
                        + "enabled INTEGER NOT NULL DEFAULT 1,"
                        + "created_at TEXT NOT NULL,"
                        + "updated_at TEXT NOT NULL,"
                        + "last_run_at TEXT,"
                        + "last_session_id TEXT,"
                        + "user_id TEXT)"
        );
        // 无登录上下文(userId=null) → 不过滤, 本类沿用原有非隔离断言; 隔离语义见 SqliteScheduledTaskRepoIsolationTest
        CurrentUserProvider provider = new CurrentUserProvider(() -> Optional.<LoginUser>empty());
        repo = new SqliteScheduledTaskRepo(jdbc, provider);
    }

    @Test
    public void save_then_findById_should_round_trip_all_fields() {
        ScheduledTask task = new ScheduledTask("nightly-report", "0 0 2 * * ?",
                "生成昨日报告", "D:/workspace/demo");

        repo.save(task);

        ScheduledTask loaded = repo.findById(task.getId());
        assertNotNull(loaded);
        assertEquals(task.getId(), loaded.getId());
        assertEquals("nightly-report", loaded.getName());
        assertEquals("0 0 2 * * ?", loaded.getCronExpr());
        assertEquals("生成昨日报告", loaded.getPrompt());
        assertEquals("D:/workspace/demo", loaded.getWorkingDir());
        assertTrue(loaded.isEnabled());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
        assertNull(loaded.getLastRunAt());
        assertNull(loaded.getLastSessionId());
    }

    @Test
    public void update_should_persist_mutable_fields_and_keep_id() {
        ScheduledTask task = new ScheduledTask("origin", "0 0 1 * * ?",
                "prompt-v1", "/tmp/v1");
        repo.save(task);

        task.setName("renamed");
        task.setCronExpr("0 0 3 * * ?");
        task.setPrompt("prompt-v2");
        task.setWorkingDir("/tmp/v2");
        task.setEnabled(false);
        repo.update(task);

        ScheduledTask loaded = repo.findById(task.getId());
        assertEquals("renamed", loaded.getName());
        assertEquals("0 0 3 * * ?", loaded.getCronExpr());
        assertEquals("prompt-v2", loaded.getPrompt());
        assertEquals("/tmp/v2", loaded.getWorkingDir());
        assertFalse(loaded.isEnabled());
    }

    @Test
    public void findAll_should_return_in_created_desc_order_and_findAllEnabled_filters_disabled() {
        ScheduledTask older = new ScheduledTask("older", "0 0 1 * * ?", "p1", "/tmp/a");
        sleepMillis(20);
        ScheduledTask newer = new ScheduledTask("newer", "0 0 2 * * ?", "p2", "/tmp/b");
        newer.setEnabled(false);
        sleepMillis(20);
        ScheduledTask newest = new ScheduledTask("newest", "0 0 3 * * ?", "p3", "/tmp/c");

        repo.save(older);
        repo.save(newer);
        repo.save(newest);

        List<ScheduledTask> all = repo.findAll();
        assertEquals(3, all.size());
        assertEquals("newest", all.get(0).getName());
        assertEquals("newer", all.get(1).getName());
        assertEquals("older", all.get(2).getName());

        List<ScheduledTask> enabled = repo.findAllEnabled();
        assertEquals(2, enabled.size());
        assertEquals("newest", enabled.get(0).getName());
        assertEquals("older", enabled.get(1).getName());
    }

    @Test
    public void updateLastRun_should_persist_last_run_at_and_session_id() {
        ScheduledTask task = new ScheduledTask("with-run", "0 0 1 * * ?", "p", "/tmp");
        repo.save(task);

        Instant ranAt = Instant.parse("2026-05-25T08:30:00Z");
        repo.updateLastRun(task.getId(), ranAt, "session-abc");

        ScheduledTask loaded = repo.findById(task.getId());
        assertEquals(ranAt, loaded.getLastRunAt());
        assertEquals("session-abc", loaded.getLastSessionId());
    }

    @Test
    public void deleteById_should_remove_record_and_findById_returns_null() {
        ScheduledTask task = new ScheduledTask("to-be-deleted", "0 0 1 * * ?", "p", "/tmp");
        repo.save(task);
        assertNotNull(repo.findById(task.getId()));

        repo.deleteById(task.getId());

        assertNull(repo.findById(task.getId()));
        assertEquals(0, repo.findAll().size());
    }

    /**
     * 复现 commit 323dbdf 场景:cleanup DELETE 偶发撞 SQLite 表级锁,
     * 重试退避能在前 N 次失败后继续成功。
     * <p>
     * 这里用前 2 次失败的"脏写"伪装在途锁(BUSY 的对外契约就是 DataAccessException),
     * 第 3 次落到真实 repo.deleteById 真正删行。这样既验证 backoff 路径被走过,
     * 又不依赖 SQLite-jdbc 的 busy_handler / busy_timeout 细节(实测 driver 内部
     * 会自带重试,光靠两连接竞争锁难以稳定触发对外的 SQLITE_BUSY)。
     */
    @Test
    public void deleteById_retry_backoff_scenario_should_succeed_after_initial_failures() {
        ScheduledTask task = new ScheduledTask("retry-target", "0 0 1 * * ?", "p", "/tmp");
        repo.save(task);

        AtomicInteger attempts = new AtomicInteger();
        int failFirstN = 2;
        Runnable flakyDelete = () -> {
            int n = attempts.incrementAndGet();
            if (n <= failFirstN) {
                throw new TransientDataAccessException(
                        "simulated SQLITE_BUSY on attempt " + n);
            }
            repo.deleteById(task.getId());
        };

        long startMs = System.currentTimeMillis();
        deleteWithRetry(flakyDelete);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertEquals(failFirstN + 1, attempts.get(),
                "应在第 " + (failFirstN + 1) + " 次成功,实际尝试=" + attempts.get());
        assertTrue(elapsedMs >= failFirstN * 50L,
                "至少应等过 " + (failFirstN * 50L) + "ms backoff,实际=" + elapsedMs + "ms");
        assertNull(repo.findById(task.getId()), "重试成功后行应已删除");
    }

    private void deleteWithRetry(Runnable deleteAction) {
        DataAccessException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                deleteAction.run();
                return;
            } catch (DataAccessException e) {
                last = e;
                sleepMillis(50);
            }
        }
        throw last;
    }

    private void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * 模拟 SQLite-jdbc 抛出的可重试 DataAccessException,
     * 对应 SQLITE_BUSY / SQLITE_LOCKED_SHAREDCACHE 在 Spring JDBC 翻译后的形态。
     */
    private static class TransientDataAccessException extends DataAccessException {
        TransientDataAccessException(String msg) { super(msg); }
    }
}
