package com.example.agentweb.infra.refinery;

import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.app.refinery.RecallObservationStart;
import com.example.agentweb.app.refinery.RecallStats;
import com.example.agentweb.app.refinery.RecallStatus;
import com.example.agentweb.app.refinery.RecallTrace;
import com.example.agentweb.app.refinery.ScoredRecallHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight SQLite integration tests for the recall observation writer:
 * real SQLite plus hand-built tables, without Spring.
 *
 * @author codex
 * @since 2026-06-12
 */
class SqliteRecallObservationRecorderTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private RecallObservationRecorder recorder;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("recall-observation.db"));
        jdbc = new JdbcTemplate(ds);
        createTables();
        recorder = new SqliteRecallObservationRecorder(jdbc);
    }

    @Test
    void tryCreateStart_pending_shouldInsertAttemptWithTruncatedQuery() {
        String longQuery = repeat('x', 2100);

        Optional<String> attemptId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 10L, longQuery, true, "test",
                RecallStatus.PENDING, null));

        assertTrue(attemptId.isPresent());
        Map<String, Object> row = jdbc.queryForMap("SELECT session_id, user_message_id, recall_enabled, "
                        + "env, status, query, created_at, updated_at FROM chat_recall_attempt WHERE id = ?",
                attemptId.get());
        assertEquals("s1", row.get("session_id"));
        assertEquals(10L, ((Number) row.get("user_message_id")).longValue());
        assertEquals(1, ((Number) row.get("recall_enabled")).intValue());
        assertEquals("test", row.get("env"));
        assertEquals("PENDING", row.get("status"));
        assertEquals(2000, ((String) row.get("query")).length());
        assertNotNull(row.get("created_at"));
        assertNotNull(row.get("updated_at"));
    }

    @Test
    void tryCreateStart_nullQuery_shouldStoreEmptyStringForNotNullColumn() {
        Optional<String> attemptId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 10L, null, true, "test",
                RecallStatus.PENDING, null));

        assertTrue(attemptId.isPresent());
        String query = jdbc.queryForObject("SELECT query FROM chat_recall_attempt WHERE id = ?",
                String.class, attemptId.get());
        assertEquals("", query);
    }

    @Test
    void tryCreateStart_skipped_shouldInsertFinalSkippedAttempt() {
        Optional<String> attemptId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 11L, "q", false, "test",
                RecallStatus.SKIPPED, "DISABLED_BY_CLIENT"));

        assertTrue(attemptId.isPresent());
        Map<String, Object> row = jdbc.queryForMap("SELECT status, skip_reason, hit_count "
                        + "FROM chat_recall_attempt WHERE id = ?",
                attemptId.get());
        assertEquals("SKIPPED", row.get("status"));
        assertEquals("DISABLED_BY_CLIENT", row.get("skip_reason"));
        assertEquals(0, ((Number) row.get("hit_count")).intValue());
    }

    @Test
    void tryRecordTrace_hit_shouldUpdateAttemptAndInsertHits() {
        String attemptId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 12L, "退款", true, "test",
                RecallStatus.PENDING, null)).orElseThrow(AssertionError::new);
        RecallTrace trace = RecallTrace.hit("退款", "augmented",
                Collections.singletonList(scoredHit("chunk-1")),
                stats(), 123L);

        recorder.tryRecordTrace(attemptId, trace);

        Map<String, Object> attempt = jdbc.queryForMap("SELECT status, hit_count, active_count, "
                        + "filtered_count, below_vector_floor, bad_vector_count, ranked_count, "
                        + "top_vector_score, top_final_score, embedding_model, embedding_dimension, "
                        + "latency_ms, params_json, error_message FROM chat_recall_attempt WHERE id = ?",
                attemptId);
        assertEquals("HIT", attempt.get("status"));
        assertEquals(1, ((Number) attempt.get("hit_count")).intValue());
        assertEquals(5, ((Number) attempt.get("active_count")).intValue());
        assertEquals(4, ((Number) attempt.get("filtered_count")).intValue());
        assertEquals(2, ((Number) attempt.get("below_vector_floor")).intValue());
        assertEquals(1, ((Number) attempt.get("bad_vector_count")).intValue());
        assertEquals(2, ((Number) attempt.get("ranked_count")).intValue());
        assertEquals(0.91d, ((Number) attempt.get("top_vector_score")).doubleValue(), 1e-9);
        assertEquals(0.88d, ((Number) attempt.get("top_final_score")).doubleValue(), 1e-9);
        assertEquals("qwen", attempt.get("embedding_model"));
        assertEquals(1024, ((Number) attempt.get("embedding_dimension")).intValue());
        assertEquals(123L, ((Number) attempt.get("latency_ms")).longValue());
        assertTrue(((String) attempt.get("params_json")).contains("\"minVectorScore\":0.6"));

        Map<String, Object> hit = jdbc.queryForMap("SELECT rank_no, chunk_id, source_session_id, "
                        + "source_msg_range, title, conclusion, final_score, source_type, tier, env "
                        + "FROM chat_recall_hit WHERE attempt_id = ?",
                attemptId);
        assertEquals(1, ((Number) hit.get("rank_no")).intValue());
        assertEquals("chunk-1", hit.get("chunk_id"));
        assertEquals("source-s1", hit.get("source_session_id"));
        assertEquals("1-3", hit.get("source_msg_range"));
        assertEquals("title", hit.get("title"));
        assertEquals("conclusion", hit.get("conclusion"));
        assertEquals(0.88d, ((Number) hit.get("final_score")).doubleValue(), 1e-9);
        assertEquals("CHAT", hit.get("source_type"));
        assertEquals("EXPLORATORY", hit.get("tier"));
        assertEquals("test", hit.get("env"));
        assertNull(attempt.get("error_message"));
    }

    @Test
    void tryRecordTrace_error_shouldTruncateErrorMessage() {
        String attemptId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 13L, "q", true, null,
                RecallStatus.PENDING, null)).orElseThrow(AssertionError::new);

        recorder.tryRecordTrace(attemptId, RecallTrace.error("q", "q", "IllegalStateException",
                repeat('e', 600), 10L));

        Map<String, Object> row = jdbc.queryForMap("SELECT status, error_type, error_message "
                        + "FROM chat_recall_attempt WHERE id = ?",
                attemptId);
        assertEquals("ERROR", row.get("status"));
        assertEquals("IllegalStateException", row.get("error_type"));
        assertEquals(500, ((String) row.get("error_message")).length());
    }

    @Test
    void tryAttachAssistantMessage_shouldBackfillAssistantMessageId() {
        String attemptId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 14L, "q", true, null,
                RecallStatus.PENDING, null)).orElseThrow(AssertionError::new);

        recorder.tryAttachAssistantMessage(attemptId, 99L);

        Long msgId = jdbc.queryForObject("SELECT assistant_message_id FROM chat_recall_attempt WHERE id = ?",
                Long.class, attemptId);
        assertEquals(Long.valueOf(99L), msgId);
    }

    @Test
    void tryDeleteBySessionId_shouldDeleteAttemptsAndHits() {
        String attemptId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 15L, "q", true, null,
                RecallStatus.PENDING, null)).orElseThrow(AssertionError::new);
        recorder.tryRecordTrace(attemptId, RecallTrace.hit("q", "augmented",
                Collections.singletonList(scoredHit("chunk-1")), stats(), 1L));

        recorder.tryDeleteBySessionId("s1");

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_recall_attempt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_recall_hit", Integer.class));
    }

    @Test
    void tryDeleteByMessageRange_shouldDeleteByUserMessageAndNullDetachedAssistant() {
        String keepId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 20L, "keep", true, null,
                RecallStatus.PENDING, null)).orElseThrow(AssertionError::new);
        String deleteId = recorder.tryCreateStart(new RecallObservationStart(
                "s1", 30L, "delete", true, null,
                RecallStatus.PENDING, null)).orElseThrow(AssertionError::new);
        recorder.tryAttachAssistantMessage(keepId, 31L);
        recorder.tryAttachAssistantMessage(deleteId, 32L);

        recorder.tryDeleteByMessageRange("s1", 30L);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_recall_attempt", Integer.class));
        assertFalse(jdbc.queryForList("SELECT id FROM chat_recall_attempt WHERE id = ?", deleteId).size() > 0);
        Map<String, Object> keep = jdbc.queryForMap("SELECT assistant_message_id FROM chat_recall_attempt WHERE id = ?",
                keepId);
        assertNull(keep.get("assistant_message_id"), "fromId 落在 assistant 后应置空悬挂 assistant_message_id");
    }

    private RecallStats stats() {
        return new RecallStats(5, 4, 2, 2, 1,
                0.91d, 0.88d, 3, false, true, 30d,
                0.6d, 0.1d, 0.5d, 0.7d, 0.2d, 0.1d,
                "qwen", 1024);
    }

    private ScoredRecallHit scoredHit(String chunkId) {
        return new ScoredRecallHit(chunkId, 1, "title", "conclusion",
                "source-s1", "1-3", 0.88d, 0.91d, 0.2d, 0.3d,
                "doubao", "CHAT", "EXPLORATORY", "test", 0.8d,
                Instant.parse("2026-06-01T10:00:00Z"));
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE chat_recall_attempt ("
                + "id TEXT PRIMARY KEY,"
                + "session_id TEXT NOT NULL,"
                + "user_message_id INTEGER NOT NULL,"
                + "assistant_message_id INTEGER,"
                + "query TEXT NOT NULL,"
                + "recall_enabled INTEGER NOT NULL,"
                + "env TEXT,"
                + "status TEXT NOT NULL,"
                + "skip_reason TEXT,"
                + "hit_count INTEGER NOT NULL DEFAULT 0,"
                + "top_k INTEGER,"
                + "active_count INTEGER,"
                + "filtered_count INTEGER,"
                + "below_vector_floor INTEGER,"
                + "bad_vector_count INTEGER,"
                + "ranked_count INTEGER,"
                + "top_vector_score REAL,"
                + "top_final_score REAL,"
                + "params_json TEXT,"
                + "embedding_model TEXT,"
                + "embedding_dimension INTEGER,"
                + "latency_ms INTEGER,"
                + "error_type TEXT,"
                + "error_message TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        jdbc.execute("CREATE UNIQUE INDEX uk_chat_recall_attempt_user_msg "
                + "ON chat_recall_attempt(user_message_id)");
        jdbc.execute("CREATE TABLE chat_recall_hit ("
                + "attempt_id TEXT NOT NULL,"
                + "rank_no INTEGER NOT NULL,"
                + "chunk_id TEXT NOT NULL,"
                + "source_session_id TEXT,"
                + "source_msg_range TEXT,"
                + "title TEXT,"
                + "conclusion TEXT,"
                + "final_score REAL,"
                + "vector_score REAL,"
                + "signal_score REAL,"
                + "time_score REAL,"
                + "embedding_model TEXT,"
                + "source_type TEXT,"
                + "tier TEXT,"
                + "env TEXT,"
                + "chunk_score REAL,"
                + "chunk_created_at INTEGER,"
                + "created_at INTEGER NOT NULL,"
                + "PRIMARY KEY (attempt_id, rank_no))");
    }

    private String repeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
