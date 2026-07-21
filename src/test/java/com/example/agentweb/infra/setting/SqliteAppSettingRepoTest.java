package com.example.agentweb.infra.setting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteAppSettingRepo} 轻量集成测试:真实 SQLite + {@code @TempDir},不起 Spring。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
class SqliteAppSettingRepoTest {

    @TempDir
    Path tempDir;

    SqliteAppSettingRepo repo;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_setting ("
                + "setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        repo = new SqliteAppSettingRepo(jdbc);
    }

    @Test
    void get_absentKey_shouldReturnEmpty() {
        assertFalse(repo.get("chat.default-agent").isPresent());
    }

    @Test
    void put_thenGet_shouldReturnValue() {
        repo.put("chat.default-agent", "CLAUDE", 1000L);
        Optional<String> v = repo.get("chat.default-agent");
        assertTrue(v.isPresent());
        assertEquals("CLAUDE", v.get());
    }

    @Test
    void put_existingKey_shouldUpsertOverwrite() {
        repo.put("chat.default-agent", "CODEX", 1000L);
        repo.put("chat.default-agent", "CLAUDE", 2000L);
        assertEquals("CLAUDE", repo.get("chat.default-agent").orElse(null));
        // 只有一行(主键冲突走 UPDATE 而非新增)
        Long count = new JdbcTemplate(dataSource()).queryForObject(
                "SELECT COUNT(*) FROM app_setting WHERE setting_key = ?", Long.class, "chat.default-agent");
        assertEquals(1L, count);
    }

    private SQLiteDataSource dataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        return ds;
    }
}
