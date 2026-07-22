package com.example.agentweb.infra.auth;

import com.example.agentweb.app.auth.AdminUserView;
import com.example.agentweb.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link SqliteUserAccountQueryService} 真实 SQLite 读模型测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SqliteUserAccountQueryServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteUserAccountQueryService queryService;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("user-query.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE user_account (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, "
                + "password_hash TEXT NOT NULL, role TEXT NOT NULL, enabled INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        queryService = new SqliteUserAccountQueryService(jdbc);
    }

    @Test
    void listAll_should_ReturnSafeProjectionOrderedByCreationTimeDescending() {
        insert("older", "older-user", "older-hash", "USER", 1, 1000L, 1000L);
        insert("newer", "newer-admin", "newer-hash", "ADMIN", 0, 2000L, 3000L);

        List<AdminUserView> users = queryService.listAll();

        assertEquals(2, users.size());
        assertEquals("newer", users.get(0).getId());
        assertEquals("newer-admin", users.get(0).getUsername());
        assertEquals(UserRole.ADMIN, users.get(0).getRole());
        assertFalse(users.get(0).isEnabled());
        assertEquals(3000L, users.get(0).getUpdatedAt().toEpochMilli());
        assertEquals("older", users.get(1).getId());
    }

    private void insert(String id, String username, String passwordHash, String role,
                        int enabled, long createdAt, long updatedAt) {
        jdbc.update("INSERT INTO user_account "
                        + "(id, username, password_hash, role, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, username, passwordHash, role, enabled, createdAt, updatedAt);
    }
}
