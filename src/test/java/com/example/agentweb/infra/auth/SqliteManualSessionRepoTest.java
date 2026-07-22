package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.ManualSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteManualSessionRepo} 轻量集成测试: 真实 SQLite + {@code @TempDir}, 不起 Spring。
 *
 * @author zhourui(V33215020)
 */
class SqliteManualSessionRepoTest {

    @TempDir
    Path tempDir;

    private SqliteManualSessionRepo repo;
    private final Clock fixed = Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        File dbFile = tempDir.resolve("manual-session-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE manual_session ("
                + "session_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, user_name TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)");
        repo = new SqliteManualSessionRepo(jdbc);
    }

    @Test
    void save_then_findById_should_roundtrip() {
        ManualSession s = ManualSession.create("V33215020", "周锐", 604800, fixed);
        repo.save(s);

        Optional<ManualSession> hit = repo.findById(s.getSessionId());
        assertTrue(hit.isPresent());
        assertEquals("V33215020", hit.get().getUserId());
        assertEquals("周锐", hit.get().getUserName());
        assertEquals(s.getCreatedAt(), hit.get().getCreatedAt());
        assertEquals(s.getExpiresAt(), hit.get().getExpiresAt());
    }

    @Test
    void findById_missing_should_be_empty() {
        assertFalse(repo.findById("nope").isPresent());
        assertFalse(repo.findById(null).isPresent());
        assertFalse(repo.findById("").isPresent());
    }

    @Test
    void save_with_same_sessionId_should_upsert() {
        ManualSession s = ManualSession.create("uid", "name", 60, fixed);
        repo.save(s);
        repo.save(s);

        assertTrue(repo.findById(s.getSessionId()).isPresent());
    }

    @Test
    void deleteById_should_remove() {
        ManualSession s = ManualSession.create("uid", "name", 60, fixed);
        repo.save(s);

        repo.deleteById(s.getSessionId());

        assertFalse(repo.findById(s.getSessionId()).isPresent());
    }

    @Test
    void deleteById_missing_should_be_noop() {
        repo.deleteById("nope");
        repo.deleteById(null);
        repo.deleteById("");
    }

    @Test
    void deleteByUserId_should_RevokeOnlyThatUsersSessions() {
        ManualSession first = ManualSession.create("admin", "admin", 600, fixed);
        ManualSession second = ManualSession.create("admin", "admin", 600, fixed);
        ManualSession other = ManualSession.create("other", "Other", 600, fixed);
        repo.save(first);
        repo.save(second);
        repo.save(other);

        int removed = repo.deleteByUserId("admin");

        assertEquals(2, removed);
        assertFalse(repo.findById(first.getSessionId()).isPresent());
        assertFalse(repo.findById(second.getSessionId()).isPresent());
        assertTrue(repo.findById(other.getSessionId()).isPresent());
    }

    @Test
    void deleteExpiredBefore_should_only_remove_expired() {
        ManualSession alive = ManualSession.create("alice", "Alice", 600, fixed);
        ManualSession dead = ManualSession.create("bob", "Bob", 10, fixed);
        repo.save(alive);
        repo.save(dead);

        // 推进到 60 秒后: dead(10s) 应过期, alive(600s) 仍活
        int removed = repo.deleteExpiredBefore(fixed.instant().plusSeconds(60));

        assertEquals(1, removed);
        assertTrue(repo.findById(alive.getSessionId()).isPresent());
        assertFalse(repo.findById(dead.getSessionId()).isPresent());
    }
}
