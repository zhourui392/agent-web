package com.example.agentweb.infra.metrics;

import com.example.agentweb.app.metrics.RecallAttemptDetail;
import com.example.agentweb.app.metrics.RecallAttemptPage;
import com.example.agentweb.app.metrics.RecallBucketMetric;
import com.example.agentweb.app.metrics.RecallChunkStat;
import com.example.agentweb.app.metrics.RecallMetricsFilter;
import com.example.agentweb.app.metrics.RecallMetricsSummary;
import com.example.agentweb.app.metrics.RecallScorePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Recall metrics read-side SQL tests.
 *
 * @author codex
 * @since 2026-06-12
 */
class SqliteRecallMetricsQueryServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteRecallMetricsQueryService service;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("recall-metrics.db"));
        jdbc = new JdbcTemplate(ds);
        service = new SqliteRecallMetricsQueryService(jdbc);
        createTables();
        seedData();
    }

    @Test
    void summary_shouldCalculateCoreRatiosIgnoringPendingForQualityDenominators() {
        RecallMetricsSummary summary = service.summary(RecallMetricsFilter.timeRange(1000L, 6000L));

        assertEquals(5L, summary.getAttemptCount());
        assertEquals(4L, summary.getExecutedCount());
        assertEquals(2L, summary.getHitCount());
        assertEquals(1L, summary.getNoHitCount());
        assertEquals(1L, summary.getErrorCount());
        assertEquals(1L, summary.getSkippedCount());
        assertEquals(1L, summary.getPendingCount());
        assertEquals(2.0 / 3.0, summary.getQualityHitRate(), 1e-9);
        assertEquals(2.0 / 4.0, summary.getUserVisibleHitRate(), 1e-9);
        assertEquals(3.0 / 4.0, summary.getServiceAvailabilityRate(), 1e-9);
        assertEquals(1.0 / 3.0, summary.getNoHitRate(), 1e-9);
        assertEquals(1.0 / 4.0, summary.getErrorRate(), 1e-9);
        assertEquals(2.0, summary.getAvgHitCount(), 1e-9);
        assertEquals(175.0, summary.getAvgLatencyMs(), 1e-9);
        assertEquals(2L, summary.getByStatus().get("HIT"));
        assertEquals(3L, summary.getByEmbeddingModel().get("qwen"));
        assertEquals(3L, summary.getByEnv().get("test"));
        assertEquals(1L, summary.getBySourceType().get("CHAT"));
        assertEquals(1L, summary.getByTier().get("EXPLORATORY"));
    }

    @Test
    void listAttempts_shouldFilterStatusAndReturnQuerySummaryNewestFirst() {
        RecallAttemptPage page = service.listAttempts(1, 2,
                new RecallMetricsFilter("HIT", null, 0L, 9999L, null, null, null, null));

        assertEquals(2L, page.getTotal());
        assertEquals(1, page.getPage());
        assertEquals(2, page.getSize());
        assertEquals(2, page.getItems().size());
        assertEquals("a-hit-2", page.getItems().get(0).getId());
        assertEquals("HIT", page.getItems().get(0).getStatus());
        assertEquals(60, page.getItems().get(0).getQuerySummary().length());
    }

    @Test
    void listAttempts_shouldFilterByModelEnvSourceTypeAndTier() {
        RecallAttemptPage chatPage = service.listAttempts(1, 20,
                new RecallMetricsFilter(null, null, 0L, 9999L,
                        "qwen", "test", "CHAT", "EXPLORATORY"));

        assertEquals(1L, chatPage.getTotal());
        assertEquals("a-hit-1", chatPage.getItems().get(0).getId());

        RecallAttemptPage diagnosePage = service.listAttempts(1, 20,
                new RecallMetricsFilter(null, null, 0L, 9999L,
                        "doubao", "prod", "DIAGNOSE", "VERIFIED"));

        assertEquals(1L, diagnosePage.getTotal());
        assertEquals("a-hit-2", diagnosePage.getItems().get(0).getId());
    }

    @Test
    void summary_shouldApplyModelEnvSourceTypeAndTierFilters() {
        RecallMetricsSummary summary = service.summary(new RecallMetricsFilter(null, null, 0L, 9999L,
                "qwen", "test", "CHAT", "EXPLORATORY"));

        assertEquals(1L, summary.getAttemptCount());
        assertEquals(1L, summary.getExecutedCount());
        assertEquals(1L, summary.getHitCount());
        assertEquals(0L, summary.getNoHitCount());
        assertEquals(1.0d, summary.getQualityHitRate(), 1e-9);
        assertEquals(1L, summary.getByEmbeddingModel().get("qwen"));
    }

    @Test
    void summary_shouldReturnQualityBucketsAndScoreSamplesForThresholdTuning() {
        RecallMetricsSummary summary = service.summary(RecallMetricsFilter.timeRange(0L, 9999L));

        RecallBucketMetric envBucket = summary.getEnvBuckets().stream()
                .filter(row -> "test".equals(row.getKey()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(1L, envBucket.getHitCount());
        assertEquals(1L, envBucket.getNoHitCount());
        assertEquals(1L, envBucket.getErrorCount());
        assertEquals(1.0 / 2.0, envBucket.getQualityHitRate(), 1e-9);

        RecallBucketMetric sourceBucket = summary.getSourceTypeBuckets().stream()
                .filter(row -> "CHAT".equals(row.getKey()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(1L, sourceBucket.getHitCount());
        assertEquals(1.0d, sourceBucket.getQualityHitRate(), 1e-9);

        List<RecallScorePoint> samples = summary.getScoreSamples();
        assertEquals(3, samples.size(), "score 分布只包含 HIT/NO_HIT 样本");
        assertEquals("a-hit-2", samples.get(0).getAttemptId());
        assertEquals(0.91d, samples.get(0).getTopVectorScore(), 1e-9);
    }

    @Test
    void detail_shouldReturnAttemptAndHits() {
        RecallAttemptDetail detail = service.detail("a-hit-1");

        assertNotNull(detail);
        assertEquals("a-hit-1", detail.getId());
        assertEquals("full query", detail.getQuery());
        assertEquals("HIT", detail.getStatus());
        assertEquals(1, detail.getHits().size());
        assertEquals("chunk-1", detail.getHits().get(0).getChunkId());
        assertEquals(0.91d, detail.getHits().get(0).getVectorScore(), 1e-9);
    }

    @Test
    void detailByMessageId_shouldFindAttemptByUserOrAssistantMessageId() {
        jdbc.update("UPDATE chat_recall_attempt SET assistant_message_id = ? WHERE id = ?", 1001L, "a-hit-1");

        assertEquals("a-hit-1", service.detailByMessageId(1000L).getId());
        assertEquals("a-hit-1", service.detailByMessageId(1001L).getId());
        assertNull(service.detailByMessageId(99999L));
    }

    @Test
    void topChunks_shouldReturnRecallUsageOrderedByRecalledTimes() {
        jdbc.update("INSERT INTO chat_recall_hit VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "a-hit-2", 2, "chunk-1", "source-s1", "1-3", "title", "conclusion",
                0.68d, 0.71d, 0.2d, 0.3d, "qwen", "CHAT", "EXPLORATORY", "test",
                0.8d, 100L, 5000L);

        List<RecallChunkStat> chunks = service.topChunks(10, RecallMetricsFilter.timeRange(0L, 9999L));

        assertEquals(2, chunks.size());
        assertEquals("chunk-1", chunks.get(0).getChunkId());
        assertEquals(2L, chunks.get(0).getRecalledTimes());
        assertEquals((0.91d + 0.71d) / 2.0d, chunks.get(0).getAvgVectorScore(), 1e-9);
        assertEquals((0.88d + 0.68d) / 2.0d, chunks.get(0).getAvgFinalScore(), 1e-9);
        assertEquals(5000L, chunks.get(0).getLastRecalledAt());
    }

    @Test
    void detail_missing_shouldReturnNull() {
        assertNull(service.detail("missing"));
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

    private void seedData() {
        insertAttempt("a-hit-1", "s1", "full query", "HIT", true, 1, 100L, "qwen", "test", 1000L);
        insertAttempt("a-no-hit", "s1", "no hit query", "NO_HIT", true, 0, 200L, "qwen", "test", 2000L);
        insertAttempt("a-error", "s2", "error query", "ERROR", true, 0, 300L, "qwen", "test", 3000L);
        insertAttempt("a-skip", "s2", "skip query", "SKIPPED", false, 0, null, null, "test", 4000L);
        insertAttempt("a-hit-2", "s3", repeat('x', 80), "HIT", true, 3, 100L, "doubao", "prod", 5000L);
        insertAttempt("a-pending", "s4", "pending query", "PENDING", true, 0, null, null, "test", 6000L);
        jdbc.update("UPDATE chat_recall_attempt SET skip_reason = ? WHERE id = ?",
                "DISABLED_BY_CLIENT", "a-skip");
        jdbc.update("UPDATE chat_recall_attempt SET error_type = ?, error_message = ? WHERE id = ?",
                "IllegalStateException", "ark down", "a-error");
        jdbc.update("INSERT INTO chat_recall_hit VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "a-hit-1", 1, "chunk-1", "source-s1", "1-3", "title", "conclusion",
                0.88d, 0.91d, 0.2d, 0.3d, "qwen", "CHAT", "EXPLORATORY", "test",
                0.8d, 100L, 1000L);
        jdbc.update("INSERT INTO chat_recall_hit VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "a-hit-2", 1, "chunk-2", "source-s2", "4-6", "title2", "conclusion2",
                0.78d, 0.81d, 0.1d, 0.2d, "doubao", "DIAGNOSE", "VERIFIED", "prod",
                0.9d, 200L, 5000L);
    }

    private void insertAttempt(String id, String sessionId, String query, String status,
                               boolean enabled, int hitCount, Long latencyMs,
                               String model, String env, long createdAt) {
        jdbc.update("INSERT INTO chat_recall_attempt ("
                        + "id, session_id, user_message_id, query, recall_enabled, env, status, "
                        + "hit_count, top_k, active_count, filtered_count, below_vector_floor, "
                        + "bad_vector_count, ranked_count, top_vector_score, top_final_score, "
                        + "params_json, embedding_model, embedding_dimension, latency_ms, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, sessionId, createdAt, query, enabled ? 1 : 0, env, status,
                hitCount, 3, 10, 8, 1, 0, 5, 0.91d, 0.88d,
                "{\"minVectorScore\":0.6}", model, model == null ? null : 1024, latencyMs,
                createdAt, createdAt);
    }

    private String repeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
