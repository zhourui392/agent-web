package com.example.agentweb.infra.nativeagent;

import com.example.agentweb.domain.diagnosis.DiagnosisCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author alex
 * @since 2026-07-29
 */
class SqliteDiagnosisCheckpointRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteDiagnosisCheckpointRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("checkpoint.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_message (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, "
                + "timestamp TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE native_diagnosis_checkpoint ("
                + "run_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
                + "user_message_id INTEGER NOT NULL, assistant_message_id INTEGER NOT NULL, "
                + "state_snapshot TEXT NOT NULL, snapshot_schema_version TEXT, "
                + "input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, "
                + "cache_read_input_tokens INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, "
                + "UNIQUE (assistant_message_id))");
        repository = new SqliteDiagnosisCheckpointRepository(jdbc);
    }

    @Test
    void findLatestValidBefore_shouldReturnNewestExtantSuccessfulBoundary() {
        long user1 = message("s1", "user", "q1");
        long assistant1 = message("s1", "assistant", "a1");
        repository.save(checkpoint("run-1", user1, assistant1, "state-1"));
        long user2 = message("s1", "user", "q2");
        long assistant2 = message("s1", "assistant", "a2");
        repository.save(checkpoint("run-2", user2, assistant2, "state-2"));
        long currentUser = message("s1", "user", "q3");

        Optional<DiagnosisCheckpoint> found =
                repository.findLatestValidBefore("s1", currentUser);

        assertTrue(found.isPresent());
        assertEquals("run-2", found.get().getRunId());
        assertEquals("state-2", found.get().getStateSnapshot());
        assertEquals(10L, found.get().getInputTokens());
    }

    @Test
    void findLatestValidBefore_shouldIgnoreDanglingOrFutureCheckpointRows() {
        long user = message("s1", "user", "q1");
        long assistant = message("s1", "assistant", "a1");
        repository.save(checkpoint("run-1", user, assistant, "state-1"));
        long currentUser = message("s1", "user", "q2");
        jdbc.update("DELETE FROM chat_message WHERE id = ?", assistant);

        assertFalse(repository.findLatestValidBefore("s1", currentUser).isPresent());
        assertFalse(repository.findLatestValidBefore("s1", user).isPresent());
    }

    @Test
    void cleanup_shouldDeleteOnlyCrossingBoundaryThenWholeSession() {
        long user1 = message("s1", "user", "q1");
        long assistant1 = message("s1", "assistant", "a1");
        repository.save(checkpoint("run-1", user1, assistant1, "state-1"));
        long user2 = message("s1", "user", "q2");
        long assistant2 = message("s1", "assistant", "a2");
        repository.save(checkpoint("run-2", user2, assistant2, "state-2"));

        repository.deleteCrossingBoundary("s1", user2);

        assertEquals(1, count("s1"));
        repository.deleteBySessionId("s1");
        assertEquals(0, count("s1"));
    }

    private DiagnosisCheckpoint checkpoint(String runId, long userId, long assistantId,
                                            String state) {
        return DiagnosisCheckpoint.record(runId, "s1", userId, assistantId, state, "v1",
                10L, 4L, 2L, Instant.parse("2026-07-29T10:00:00Z"));
    }

    private long message(String sessionId, String role, String content) {
        jdbc.update("INSERT INTO chat_message(session_id, role, content, timestamp) "
                        + "VALUES (?, ?, ?, ?)",
                sessionId, role, content, "2026-07-29T10:00:00Z");
        return jdbc.queryForObject("SELECT MAX(id) FROM chat_message WHERE session_id = ?",
                Long.class, sessionId);
    }

    private int count(String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM native_diagnosis_checkpoint "
                + "WHERE session_id = ?", Integer.class, sessionId);
    }
}
