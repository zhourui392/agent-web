package com.example.agentweb.infra.metrics;

import com.example.agentweb.app.metrics.RecallAttemptDetail;
import com.example.agentweb.app.metrics.RecallAttemptListItem;
import com.example.agentweb.app.metrics.RecallAttemptPage;
import com.example.agentweb.app.metrics.RecallBucketMetric;
import com.example.agentweb.app.metrics.RecallChunkStat;
import com.example.agentweb.app.metrics.RecallHitDetail;
import com.example.agentweb.app.metrics.RecallMetricsFilter;
import com.example.agentweb.app.metrics.RecallMetricsQueryService;
import com.example.agentweb.app.metrics.RecallMetricsSummary;
import com.example.agentweb.app.metrics.RecallScorePoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite read-side implementation for recall metrics.
 *
 * @author codex
 * @since 2026-06-12
 */
@Component
public class SqliteRecallMetricsQueryService implements RecallMetricsQueryService {

    private static final int QUERY_SUMMARY_MAX = 60;
    private static final String ATTEMPT_COLUMNS = "id, session_id, user_message_id, assistant_message_id, "
            + "query, recall_enabled, env, status, skip_reason, hit_count, top_k, active_count, "
            + "filtered_count, below_vector_floor, bad_vector_count, ranked_count, top_vector_score, "
            + "top_final_score, params_json, embedding_model, embedding_dimension, latency_ms, error_type, "
            + "error_message, created_at, updated_at";
    private static final String HIT_COLUMNS = "attempt_id, rank_no, chunk_id, source_session_id, "
            + "source_msg_range, title, conclusion, final_score, vector_score, signal_score, time_score, "
            + "embedding_model, source_type, tier, env, chunk_score, chunk_created_at, created_at";
    private final JdbcTemplate jdbc;

    public SqliteRecallMetricsQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RecallMetricsSummary summary(RecallMetricsFilter query) {
        Filter filter = buildAttemptFilter(query, null, true);
        RecallMetricsSummary summary = new RecallMetricsSummary();
        Map<String, Long> byStatus = groupCount("SELECT status, COUNT(*) FROM chat_recall_attempt "
                + filter.where + " GROUP BY status", filter.args);
        summary.setByStatus(byStatus);
        summary.setByEmbeddingModel(groupCount(
                "SELECT embedding_model, COUNT(*) FROM chat_recall_attempt "
                        + filter.where + " AND embedding_model IS NOT NULL AND "
                        + executedStatusClause(null) + " GROUP BY embedding_model",
                filter.args));
        summary.setByEnv(groupCount("SELECT env, COUNT(*) FROM chat_recall_attempt "
                + filter.where + " AND env IS NOT NULL AND "
                + executedStatusClause(null) + " GROUP BY env", filter.args));
        Filter joinedFilter = buildJoinedFilter(query, "a", "h");
        summary.setBySourceType(groupCount(
                "SELECT h.source_type, COUNT(DISTINCT a.id) FROM chat_recall_attempt a "
                        + "JOIN chat_recall_hit h ON h.attempt_id = a.id "
                        + joinedFilter.where
                        + " AND h.source_type IS NOT NULL GROUP BY h.source_type",
                joinedFilter.args));
        summary.setByTier(groupCount(
                "SELECT h.tier, COUNT(DISTINCT a.id) FROM chat_recall_attempt a "
                        + "JOIN chat_recall_hit h ON h.attempt_id = a.id "
                        + joinedFilter.where
                        + " AND h.tier IS NOT NULL GROUP BY h.tier",
                joinedFilter.args));

        long attempts = scalarLong("SELECT COUNT(*) FROM chat_recall_attempt "
                + filter.where + " AND recall_enabled = 1", filter.args);
        long hit = byStatus.getOrDefault("HIT", 0L);
        long noHit = byStatus.getOrDefault("NO_HIT", 0L);
        long error = byStatus.getOrDefault("ERROR", 0L);
        long skipped = byStatus.getOrDefault("SKIPPED", 0L);
        long pending = byStatus.getOrDefault("PENDING", 0L);
        long executed = hit + noHit + error;
        long available = hit + noHit;

        summary.setAttemptCount(attempts);
        summary.setExecutedCount(executed);
        summary.setHitCount(hit);
        summary.setNoHitCount(noHit);
        summary.setErrorCount(error);
        summary.setSkippedCount(skipped);
        summary.setPendingCount(pending);
        summary.setServiceAvailabilityRate(ratio(available, executed));
        summary.setQualityHitRate(ratio(hit, hit + noHit));
        summary.setUserVisibleHitRate(ratio(hit, executed));
        summary.setNoHitRate(ratio(noHit, hit + noHit));
        summary.setErrorRate(ratio(error, executed));
        summary.setAvgHitCount(scalarDouble("SELECT AVG(hit_count) FROM chat_recall_attempt "
                + filter.where + " AND status = 'HIT'", filter.args));
        summary.setAvgLatencyMs(scalarDouble("SELECT AVG(latency_ms) FROM chat_recall_attempt "
                + filter.where + " AND status IN ('HIT','NO_HIT','ERROR')", filter.args));
        summary.setEnvBuckets(bucketMetrics("env", filter));
        summary.setEmbeddingModelBuckets(bucketMetrics("embedding_model", filter));
        summary.setSourceTypeBuckets(hitBucketMetrics("source_type", joinedFilter));
        summary.setTierBuckets(hitBucketMetrics("tier", joinedFilter));
        summary.setScoreSamples(scoreSamples(filter));
        return summary;
    }

    @Override
    public RecallAttemptPage listAttempts(int page, int size, RecallMetricsFilter query) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Filter filter = buildAttemptFilter(query, null, true);
        long total = scalarLong("SELECT COUNT(*) FROM chat_recall_attempt " + filter.where, filter.args);
        List<Object> args = new ArrayList<>(filter.args);
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<RecallAttemptListItem> items = jdbc.query("SELECT " + ATTEMPT_COLUMNS + " FROM chat_recall_attempt "
                        + filter.where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                LIST_MAPPER, args.toArray());
        RecallAttemptPage out = new RecallAttemptPage();
        out.setPage(safePage);
        out.setSize(safeSize);
        out.setTotal(total);
        out.setItems(items);
        return out;
    }

    @Override
    public RecallAttemptDetail detail(String id) {
        List<RecallAttemptDetail> attempts = jdbc.query(
                "SELECT " + ATTEMPT_COLUMNS + " FROM chat_recall_attempt WHERE id = ?",
                DETAIL_MAPPER,
                id);
        if (attempts.isEmpty()) {
            return null;
        }
        RecallAttemptDetail detail = attempts.get(0);
        detail.setHits(jdbc.query("SELECT " + HIT_COLUMNS
                        + " FROM chat_recall_hit WHERE attempt_id = ? ORDER BY rank_no ASC",
                HIT_MAPPER, id));
        return detail;
    }

    @Override
    public RecallAttemptDetail detailByMessageId(long messageId) {
        List<RecallAttemptDetail> attempts = jdbc.query(
                "SELECT " + ATTEMPT_COLUMNS
                        + " FROM chat_recall_attempt WHERE user_message_id = ? OR assistant_message_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                DETAIL_MAPPER,
                messageId,
                messageId);
        if (attempts.isEmpty()) {
            return null;
        }
        RecallAttemptDetail detail = attempts.get(0);
        detail.setHits(jdbc.query("SELECT " + HIT_COLUMNS
                        + " FROM chat_recall_hit WHERE attempt_id = ? ORDER BY rank_no ASC",
                HIT_MAPPER, detail.getId()));
        return detail;
    }

    @Override
    public List<RecallChunkStat> topChunks(int limit, RecallMetricsFilter query) {
        int safeLimit = Math.min(Math.max(1, limit), 100);
        Filter filter = buildJoinedFilter(query, "a", "h");
        List<Object> args = new ArrayList<>(filter.args);
        args.add(safeLimit);
        return jdbc.query("SELECT h.chunk_id, COUNT(*) AS recalled_times, "
                        + "AVG(h.vector_score) AS avg_vector_score, AVG(h.final_score) AS avg_final_score, "
                        + "MAX(h.title) AS title, MAX(h.source_type) AS source_type, MAX(h.tier) AS tier, "
                        + "MAX(h.created_at) AS last_recalled_at "
                        + "FROM chat_recall_hit h JOIN chat_recall_attempt a ON a.id = h.attempt_id "
                        + filter.where
                        + " GROUP BY h.chunk_id ORDER BY recalled_times DESC, last_recalled_at DESC LIMIT ?",
                CHUNK_STAT_MAPPER, args.toArray());
    }

    private Filter buildAttemptFilter(RecallMetricsFilter query, String attemptRef, boolean includeHitFilter) {
        RecallMetricsFilter filter = query == null ? RecallMetricsFilter.timeRange(null, null) : query;
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String prefix = attemptRef == null || attemptRef.isBlank() ? "" : attemptRef + ".";
        if (filter.getStatus() != null) {
            where.append(" AND ").append(prefix).append("status = ?");
            args.add(filter.getStatus());
        }
        if (filter.getSessionId() != null) {
            where.append(" AND ").append(prefix).append("session_id = ?");
            args.add(filter.getSessionId());
        }
        if (filter.getFrom() != null) {
            where.append(" AND ").append(prefix).append("created_at >= ?");
            args.add(filter.getFrom());
        }
        if (filter.getTo() != null) {
            where.append(" AND ").append(prefix).append("created_at <= ?");
            args.add(filter.getTo());
        }
        if (filter.getEmbeddingModel() != null) {
            where.append(" AND ").append(prefix).append("embedding_model = ?");
            args.add(filter.getEmbeddingModel());
        }
        if (filter.getEnv() != null) {
            where.append(" AND ").append(prefix).append("env = ?");
            args.add(filter.getEnv());
        }
        if (includeHitFilter && filter.hasHitFilter()) {
            where.append(" AND EXISTS (SELECT 1 FROM chat_recall_hit hf WHERE hf.attempt_id = ")
                    .append(attemptIdRef(prefix));
            if (filter.getSourceType() != null) {
                where.append(" AND hf.source_type = ?");
                args.add(filter.getSourceType());
            }
            if (filter.getTier() != null) {
                where.append(" AND hf.tier = ?");
                args.add(filter.getTier());
            }
            where.append(")");
        }
        return new Filter(where.toString(), args);
    }

    private String attemptIdRef(String prefix) {
        return prefix == null || prefix.isEmpty() ? "chat_recall_attempt.id" : prefix + "id";
    }

    private Filter buildJoinedFilter(RecallMetricsFilter query, String attemptAlias, String hitAlias) {
        RecallMetricsFilter filter = query == null ? RecallMetricsFilter.timeRange(null, null) : query;
        Filter attemptFilter = buildAttemptFilter(filter, attemptAlias, false);
        StringBuilder where = new StringBuilder(attemptFilter.where);
        List<Object> args = new ArrayList<>(attemptFilter.args);
        if (filter.getSourceType() != null) {
            where.append(" AND ").append(hitAlias).append(".source_type = ?");
            args.add(filter.getSourceType());
        }
        if (filter.getTier() != null) {
            where.append(" AND ").append(hitAlias).append(".tier = ?");
            args.add(filter.getTier());
        }
        return new Filter(where.toString(), args);
    }

    private List<RecallBucketMetric> bucketMetrics(String column, Filter filter) {
        return jdbc.query("SELECT " + column + ", "
                        + "SUM(CASE WHEN status = 'HIT' THEN 1 ELSE 0 END) AS hit_count, "
                        + "SUM(CASE WHEN status = 'NO_HIT' THEN 1 ELSE 0 END) AS no_hit_count, "
                        + "SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END) AS error_count "
                        + "FROM chat_recall_attempt " + filter.where
                        + " AND " + column + " IS NOT NULL AND "
                        + executedStatusClause(null) + " GROUP BY " + column,
                BUCKET_MAPPER, filter.args.toArray());
    }

    private List<RecallBucketMetric> hitBucketMetrics(String column, Filter filter) {
        return jdbc.query("SELECT h." + column + ", "
                        + "COUNT(DISTINCT CASE WHEN a.status = 'HIT' THEN a.id END) AS hit_count, "
                        + "COUNT(DISTINCT CASE WHEN a.status = 'NO_HIT' THEN a.id END) AS no_hit_count, "
                        + "COUNT(DISTINCT CASE WHEN a.status = 'ERROR' THEN a.id END) AS error_count "
                        + "FROM chat_recall_attempt a JOIN chat_recall_hit h ON h.attempt_id = a.id "
                        + filter.where
                        + " AND h." + column + " IS NOT NULL AND "
                        + executedStatusClause("a") + " GROUP BY h." + column,
                BUCKET_MAPPER, filter.args.toArray());
    }

    private String executedStatusClause(String attemptRef) {
        String prefix = attemptRef == null || attemptRef.isBlank() ? "" : attemptRef + ".";
        return prefix + "status IN ('HIT','NO_HIT','ERROR')";
    }

    private List<RecallScorePoint> scoreSamples(Filter filter) {
        return jdbc.query("SELECT id, status, top_vector_score, top_final_score, below_vector_floor, "
                        + "filtered_count, bad_vector_count, ranked_count, created_at "
                        + "FROM chat_recall_attempt " + filter.where
                        + " AND status IN ('HIT','NO_HIT') ORDER BY created_at DESC LIMIT 200",
                SCORE_POINT_MAPPER, filter.args.toArray());
    }

    private long scalarLong(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    private Double scalarDouble(String sql, List<Object> args) {
        return jdbc.queryForObject(sql, Double.class, args.toArray());
    }

    private Map<String, Long> groupCount(String sql, List<Object> args) {
        Map<String, Long> result = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> {
            String key = rs.getString(1);
            result.put(key == null ? "unknown" : key, rs.getLong(2));
        };
        jdbc.query(sql, handler, args.toArray());
        return result;
    }

    private Double ratio(long numerator, long denominator) {
        return denominator == 0L ? null : (double) numerator / denominator;
    }

    private static String summary(String query) {
        if (query == null) {
            return null;
        }
        return query.length() <= QUERY_SUMMARY_MAX ? query : query.substring(0, QUERY_SUMMARY_MAX);
    }

    private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static void fillCommon(RecallAttemptListItem item, ResultSet rs) throws SQLException {
        item.setId(rs.getString("id"));
        item.setSessionId(rs.getString("session_id"));
        item.setUserMessageId(longOrNull(rs, "user_message_id"));
        item.setAssistantMessageId(longOrNull(rs, "assistant_message_id"));
        item.setQuerySummary(summary(rs.getString("query")));
        item.setRecallEnabled(rs.getInt("recall_enabled") == 1);
        item.setEnv(rs.getString("env"));
        item.setStatus(rs.getString("status"));
        item.setSkipReason(rs.getString("skip_reason"));
        item.setHitCount(rs.getInt("hit_count"));
        item.setTopK(integerOrNull(rs, "top_k"));
        item.setActiveCount(integerOrNull(rs, "active_count"));
        item.setFilteredCount(integerOrNull(rs, "filtered_count"));
        item.setBelowVectorFloor(integerOrNull(rs, "below_vector_floor"));
        item.setBadVectorCount(integerOrNull(rs, "bad_vector_count"));
        item.setRankedCount(integerOrNull(rs, "ranked_count"));
        item.setTopVectorScore(doubleOrNull(rs, "top_vector_score"));
        item.setTopFinalScore(doubleOrNull(rs, "top_final_score"));
        item.setEmbeddingModel(rs.getString("embedding_model"));
        item.setEmbeddingDimension(integerOrNull(rs, "embedding_dimension"));
        item.setLatencyMs(longOrNull(rs, "latency_ms"));
        item.setErrorType(rs.getString("error_type"));
        item.setCreatedAt(longOrNull(rs, "created_at"));
        item.setUpdatedAt(longOrNull(rs, "updated_at"));
    }

    private static final RowMapper<RecallAttemptListItem> LIST_MAPPER = (rs, rowNum) -> {
        RecallAttemptListItem item = new RecallAttemptListItem();
        fillCommon(item, rs);
        return item;
    };

    private static final RowMapper<RecallAttemptDetail> DETAIL_MAPPER = (rs, rowNum) -> {
        RecallAttemptDetail detail = new RecallAttemptDetail();
        fillCommon(detail, rs);
        detail.setQuery(rs.getString("query"));
        detail.setParamsJson(rs.getString("params_json"));
        detail.setErrorMessage(rs.getString("error_message"));
        return detail;
    };

    private static final RowMapper<RecallHitDetail> HIT_MAPPER = (rs, rowNum) -> {
        RecallHitDetail hit = new RecallHitDetail();
        hit.setRankNo(rs.getInt("rank_no"));
        hit.setChunkId(rs.getString("chunk_id"));
        hit.setSourceSessionId(rs.getString("source_session_id"));
        hit.setSourceMsgRange(rs.getString("source_msg_range"));
        hit.setTitle(rs.getString("title"));
        hit.setConclusion(rs.getString("conclusion"));
        hit.setFinalScore(doubleOrNull(rs, "final_score"));
        hit.setVectorScore(doubleOrNull(rs, "vector_score"));
        hit.setSignalScore(doubleOrNull(rs, "signal_score"));
        hit.setTimeScore(doubleOrNull(rs, "time_score"));
        hit.setEmbeddingModel(rs.getString("embedding_model"));
        hit.setSourceType(rs.getString("source_type"));
        hit.setTier(rs.getString("tier"));
        hit.setEnv(rs.getString("env"));
        hit.setChunkScore(doubleOrNull(rs, "chunk_score"));
        hit.setChunkCreatedAt(longOrNull(rs, "chunk_created_at"));
        hit.setCreatedAt(longOrNull(rs, "created_at"));
        return hit;
    };

    private static final RowMapper<RecallBucketMetric> BUCKET_MAPPER = (rs, rowNum) -> {
        RecallBucketMetric metric = new RecallBucketMetric();
        metric.setKey(rs.getString(1));
        long hit = rs.getLong("hit_count");
        long noHit = rs.getLong("no_hit_count");
        long error = rs.getLong("error_count");
        long executed = hit + noHit + error;
        metric.setHitCount(hit);
        metric.setNoHitCount(noHit);
        metric.setErrorCount(error);
        metric.setExecutedCount(executed);
        metric.setQualityHitRate(executed == 0L && hit + noHit == 0L ? null : ratioStatic(hit, hit + noHit));
        metric.setUserVisibleHitRate(ratioStatic(hit, executed));
        return metric;
    };

    private static final RowMapper<RecallScorePoint> SCORE_POINT_MAPPER = (rs, rowNum) -> {
        RecallScorePoint point = new RecallScorePoint();
        point.setAttemptId(rs.getString("id"));
        point.setStatus(rs.getString("status"));
        point.setTopVectorScore(doubleOrNull(rs, "top_vector_score"));
        point.setTopFinalScore(doubleOrNull(rs, "top_final_score"));
        point.setBelowVectorFloor(integerOrNull(rs, "below_vector_floor"));
        point.setFilteredCount(integerOrNull(rs, "filtered_count"));
        point.setBadVectorCount(integerOrNull(rs, "bad_vector_count"));
        point.setRankedCount(integerOrNull(rs, "ranked_count"));
        point.setCreatedAt(longOrNull(rs, "created_at"));
        return point;
    };

    private static final RowMapper<RecallChunkStat> CHUNK_STAT_MAPPER = (rs, rowNum) -> {
        RecallChunkStat stat = new RecallChunkStat();
        stat.setChunkId(rs.getString("chunk_id"));
        stat.setRecalledTimes(rs.getLong("recalled_times"));
        stat.setAvgVectorScore(doubleOrNull(rs, "avg_vector_score"));
        stat.setAvgFinalScore(doubleOrNull(rs, "avg_final_score"));
        stat.setTitle(rs.getString("title"));
        stat.setSourceType(rs.getString("source_type"));
        stat.setTier(rs.getString("tier"));
        stat.setLastRecalledAt(longOrNull(rs, "last_recalled_at"));
        return stat;
    };

    private static Double ratioStatic(long numerator, long denominator) {
        return denominator == 0L ? null : (double) numerator / denominator;
    }

    private static final class Filter {
        final String where;
        final List<Object> args;

        Filter(String where, List<Object> args) {
            this.where = where;
            this.args = args;
        }

    }
}
