package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteUserAccountRepository} 真实 SQLite 测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SqliteUserAccountRepositoryTest {

    @TempDir
    Path tempDir;

    private SqliteUserAccountRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("users.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE user_account (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, "
                + "password_hash TEXT NOT NULL, role TEXT NOT NULL, enabled INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        repository = new SqliteUserAccountRepository(jdbc);
    }

    @Test
    void saveAndFind_should_RoundTripAccountWithoutPlaintextPassword() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        UserAccount account = UserAccount.restore(
                "admin", "admin", "$2a$12$encoded", UserRole.ADMIN, true, now, now);

        repository.save(account);

        UserAccount loaded = repository.findByUsername("admin").orElseThrow(AssertionError::new);
        assertEquals("admin", loaded.getId());
        assertEquals(UserRole.ADMIN, loaded.getRole());
        assertEquals("$2a$12$encoded", loaded.getPasswordHash());
        assertFalse("Aa135246".equals(loaded.getPasswordHash()));
        assertTrue(repository.findById("admin").isPresent());
    }
}
