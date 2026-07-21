package com.example.agentweb.infra.git;

import com.example.agentweb.domain.git.GitIdentity;
import com.example.agentweb.domain.git.UserGitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteUserGitConfigRepo} 轻量集成（@TempDir + 真实 SQLite，不起 Spring）。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class SqliteUserGitConfigRepoTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteUserGitConfigRepo repo;

    @BeforeEach
    void setUp() {
        File dbFile = tempDir.resolve("git-config-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_git_config ("
                + "user_id TEXT PRIMARY KEY,"
                + "git_name TEXT,"
                + "git_email TEXT,"
                + "cred_username TEXT,"
                + "cred_password_enc TEXT,"
                + "updated_at INTEGER)");
        repo = new SqliteUserGitConfigRepo(jdbc);
    }

    @Test
    void save_identity_only_then_find_should_round_trip_without_credential() {
        repo.save(UserGitConfig.create("u1", GitIdentity.of("周锐", "zhourui@x.com"), NOW));

        UserGitConfig loaded = repo.findByUserId("u1").orElseThrow(AssertionError::new);
        assertEquals("周锐", loaded.getIdentity().getName());
        assertEquals("zhourui@x.com", loaded.getIdentity().getEmail());
        assertFalse(loaded.hasCredential());
        assertEquals(NOW.toEpochMilli(), loaded.getUpdatedAt().toEpochMilli());
    }

    @Test
    void save_with_credential_then_find_should_round_trip_cipher_text() {
        UserGitConfig cfg = UserGitConfig.create("u1", GitIdentity.of("n", "a@b.com"), NOW);
        cfg.updateCredential("gituser", "v1:cipherblob", NOW);
        repo.save(cfg);

        UserGitConfig loaded = repo.findByUserId("u1").orElseThrow(AssertionError::new);
        assertTrue(loaded.hasCredential());
        assertEquals("gituser", loaded.getCredentialUsername());
        assertEquals("v1:cipherblob", loaded.getCredentialPasswordCipher());
    }

    @Test
    void save_should_upsert_on_same_user() {
        repo.save(UserGitConfig.create("u1", GitIdentity.of("旧名", "old@x.com"), NOW));
        repo.save(UserGitConfig.create("u1", GitIdentity.of("新名", "new@x.com"), NOW));

        UserGitConfig loaded = repo.findByUserId("u1").orElseThrow(AssertionError::new);
        assertEquals("新名", loaded.getIdentity().getName());
        assertEquals("new@x.com", loaded.getIdentity().getEmail());
    }

    @Test
    void delete_should_remove_row() {
        repo.save(UserGitConfig.create("u1", GitIdentity.of("n", "a@b.com"), NOW));
        repo.deleteByUserId("u1");
        assertFalse(repo.findByUserId("u1").isPresent());
    }

    @Test
    void find_missing_should_return_empty() {
        assertEquals(Optional.empty(), repo.findByUserId("nope"));
        assertEquals(Optional.empty(), repo.findByUserId(null));
    }
}
