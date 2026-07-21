package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.refinery.SessionRefineryState;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class SqliteSessionRefineryStateRepoTest {

    private static final Instant T0 = Instant.parse("2026-05-28T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-28T11:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteSessionRefineryStateRepo repo;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("rag-state-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE chat_session_rag_state ("
                        + "session_id TEXT PRIMARY KEY,"
                        + "last_refined_at INTEGER NOT NULL,"
                        + "last_message_at_seen INTEGER NOT NULL,"
                        + "last_chunk_id TEXT,"
                        + "last_error TEXT,"
                        + "retry_count INTEGER NOT NULL DEFAULT 0)"
        );
        repo = new SqliteSessionRefineryStateRepo(jdbc);
    }

    @Test
    public void save_then_find_by_session_id_should_round_trip() {
        SessionRefineryState state = new SessionRefineryState(
                "sess-1", T0, T0.minusSeconds(60), "c-99", null, 0);
        repo.save(state);

        Optional<SessionRefineryState> loaded = repo.findBySessionId("sess-1");
        assertTrue(loaded.isPresent());
        SessionRefineryState got = loaded.get();
        assertEquals("sess-1", got.getSessionId());
        assertEquals(T0, got.getLastRefinedAt());
        assertEquals(T0.minusSeconds(60), got.getLastMessageAtSeen());
        assertEquals("c-99", got.getLastChunkId());
        assertNull(got.getLastError());
        assertEquals(0, got.getRetryCount());
    }

    @Test
    public void find_by_session_id_no_match_should_return_empty() {
        assertFalse(repo.findBySessionId("missing").isPresent());
    }

    @Test
    public void save_with_same_session_id_should_upsert_overwrite() {
        repo.save(new SessionRefineryState("sess-1", T0, T0.minusSeconds(60), "c-1", null, 0));
        // 二次评分: 写新状态 (含累加后的 retry_count)
        repo.save(new SessionRefineryState("sess-1", T1, T1.minusSeconds(30), "c-2", "json parse failed", 2));

        SessionRefineryState got = repo.findBySessionId("sess-1").orElseThrow(AssertionError::new);
        assertEquals(T1, got.getLastRefinedAt());
        assertEquals(T1.minusSeconds(30), got.getLastMessageAtSeen());
        assertEquals("c-2", got.getLastChunkId());
        assertEquals("json parse failed", got.getLastError());
        assertEquals(2, got.getRetryCount(), "UPSERT 应覆盖 retry_count");
    }

    @Test
    public void save_with_null_optional_fields_should_persist_as_null() {
        repo.save(new SessionRefineryState("sess-empty", T0, T0, null, null, 0));
        SessionRefineryState got = repo.findBySessionId("sess-empty").orElseThrow(AssertionError::new);
        assertNull(got.getLastChunkId());
        assertNull(got.getLastError());
    }

    @Test
    public void delete_by_session_id_should_remove_target_without_affecting_other_sessions() {
        repo.save(new SessionRefineryState("sess-1", T0, T0, "c-1", null, 0));
        repo.save(new SessionRefineryState("sess-2", T0, T0, "c-2", null, 0));

        repo.deleteBySessionId("sess-1");

        assertFalse(repo.findBySessionId("sess-1").isPresent());
        assertTrue(repo.findBySessionId("sess-2").isPresent());
    }

    @Test
    public void delete_by_session_id_for_missing_session_should_silently_succeed() {
        repo.deleteBySessionId("missing");
        assertFalse(repo.findBySessionId("missing").isPresent());
    }
}
