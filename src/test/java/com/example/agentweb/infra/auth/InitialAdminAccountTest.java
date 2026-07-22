package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 初始 admin 账户的 schema 种子集成测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class InitialAdminAccountTest {

    @Test
    void initializer_should_seedBcryptAdminOnce_withoutResettingExistingPassword(@TempDir Path tempDir)
            throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("seed.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);

        initializer.init();

        SqliteUserAccountRepository repository = new SqliteUserAccountRepository(jdbc);
        UserAccount admin = repository.findByUsername("admin").orElseThrow(AssertionError::new);
        assertEquals(UserRole.ADMIN, admin.getRole());
        assertTrue(new BCryptPasswordHasher().matches("Aa135246", admin.getPasswordHash()));

        jdbc.update("UPDATE user_account SET password_hash = ? WHERE username = 'admin'", "custom-hash");
        initializer.init();
        assertEquals("custom-hash",
                repository.findByUsername("admin").orElseThrow(AssertionError::new).getPasswordHash());
    }
}
