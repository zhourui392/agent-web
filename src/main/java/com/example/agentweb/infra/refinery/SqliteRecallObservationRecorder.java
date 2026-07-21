package com.example.agentweb.infra.refinery;

import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.app.refinery.RecallObservationStart;
import com.example.agentweb.app.refinery.RecallStats;
import com.example.agentweb.app.refinery.RecallTrace;
import com.example.agentweb.app.refinery.ScoredRecallHit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite projection writer for chat recall observability.
 *
 * @author codex
 * @since 2026-06-12
 */
@Component
@Slf4j
public class SqliteRecallObservationRecorder implements RecallObservationRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final JdbcTemplate jdbc;

    public SqliteRecallObservationRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> tryCreateStart(RecallObservationStart start) {
        try {
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO chat_recall_attempt ("
                            + "id, session_id, user_message_id, query, recall_enabled, env, "
                            + "status, skip_reason, hit_count, created_at, updated_at"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id,
                    start.getSessionId(),
                    start.getUserMessageId(),
                    truncateToEmpty(start.getQuery(), MAX_QUERY_LENGTH),
                    start.isRecallEnabled() ? 1 : 0,
                    start.getEnv(),
                    start.getStatus().name(),
                    start.getSkipReason(),
                    0,
                    now,
                    now);
            return Optional.of(id);
        } catch (Exception e) {
            log.warn("chat-recall-attempt-create-failed sessionId={} userMessageId={} reason={}",
                    start == null ? null : start.getSessionId(),
                    start == null ? null : start.getUserMessageId(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void tryRecordTrace(String attemptId, RecallTrace trace) {
        if (attemptId == null || attemptId.isEmpty() || trace == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            RecallStats stats = trace.getStats();
            jdbc.update("UPDATE chat_recall_attempt SET "
                            + "status = ?, skip_reason = ?, hit_count = ?, top_k = ?, "
                            + "active_count = ?, filtered_count = ?, below_vector_floor = ?, "
                            + "bad_vector_count = ?, ranked_count = ?, top_vector_score = ?, "
                            + "top_final_score = ?, params_json = ?, embedding_model = ?, "
                            + "embedding_dimension = ?, latency_ms = ?, error_type = ?, "
                            + "error_message = ?, updated_at = ? WHERE id = ?",
                    trace.getStatus().name(),
                    trace.getSkipReason(),
                    trace.getHits().size(),
                    stats == null ? null : stats.getTopK(),
                    stats == null ? null : stats.getActiveCount(),
                    stats == null ? null : stats.getFilteredCount(),
                    stats == null ? null : stats.getBelowVectorFloor(),
                    stats == null ? null : stats.getBadVectorCount(),
                    stats == null ? null : stats.getRankedCount(),
                    stats == null ? null : stats.getTopVectorScore(),
                    stats == null ? null : stats.getTopFinalScore(),
                    stats == null ? null : paramsJson(stats),
                    stats == null ? null : stats.getEmbeddingModel(),
                    stats == null ? null : stats.getEmbeddingDimension(),
                    trace.getLatencyMs(),
                    trace.getErrorType(),
                    truncate(trace.getErrorMessage(), MAX_ERROR_MESSAGE_LENGTH),
                    now,
                    attemptId);
            jdbc.update("DELETE FROM chat_recall_hit WHERE attempt_id = ?", attemptId);
            for (ScoredRecallHit hit : trace.getHits()) {
                insertHit(attemptId, hit, now);
            }
        } catch (Exception e) {
            log.warn("chat-recall-trace-record-failed attemptId={} reason={}", attemptId, e.getMessage());
        }
    }

    @Override
    public void tryAttachAssistantMessage(String attemptId, long assistantMessageId) {
        if (attemptId == null || attemptId.isEmpty()) {
            return;
        }
        try {
            jdbc.update("UPDATE chat_recall_attempt SET assistant_message_id = ?, updated_at = ? WHERE id = ?",
                    assistantMessageId, System.currentTimeMillis(), attemptId);
        } catch (Exception e) {
            log.warn("chat-recall-assistant-attach-failed attemptId={} assistantMessageId={} reason={}",
                    attemptId, assistantMessageId, e.getMessage());
        }
    }

    @Override
    public void tryDeleteBySessionId(String sessionId) {
        try {
            jdbc.update("DELETE FROM chat_recall_hit WHERE attempt_id IN "
                    + "(SELECT id FROM chat_recall_attempt WHERE session_id = ?)", sessionId);
            jdbc.update("DELETE FROM chat_recall_attempt WHERE session_id = ?", sessionId);
        } catch (Exception e) {
            log.warn("chat-recall-delete-session-failed sessionId={} reason={}", sessionId, e.getMessage());
        }
    }

    @Override
    public void tryDeleteByMessageRange(String sessionId, long fromId) {
        try {
            jdbc.update("DELETE FROM chat_recall_hit WHERE attempt_id IN "
                    + "(SELECT id FROM chat_recall_attempt WHERE session_id = ? AND user_message_id >= ?)",
                    sessionId, fromId);
            jdbc.update("DELETE FROM chat_recall_attempt WHERE session_id = ? AND user_message_id >= ?",
                    sessionId, fromId);
            jdbc.update("UPDATE chat_recall_attempt SET assistant_message_id = NULL, updated_at = ? "
                            + "WHERE session_id = ? AND assistant_message_id >= ?",
                    System.currentTimeMillis(), sessionId, fromId);
        } catch (Exception e) {
            log.warn("chat-recall-delete-message-range-failed sessionId={} fromId={} reason={}",
                    sessionId, fromId, e.getMessage());
        }
    }

    private void insertHit(String attemptId, ScoredRecallHit hit, long now) {
        jdbc.update("INSERT OR REPLACE INTO chat_recall_hit ("
                        + "attempt_id, rank_no, chunk_id, source_session_id, source_msg_range, "
                        + "title, conclusion, final_score, vector_score, signal_score, time_score, "
                        + "embedding_model, source_type, tier, env, chunk_score, chunk_created_at, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                attemptId,
                hit.getRankNo(),
                hit.getChunkId(),
                hit.getSourceSessionId(),
                hit.getSourceMsgRange(),
                hit.getTitle(),
                hit.getConclusion(),
                hit.getFinalScore(),
                hit.getVectorScore(),
                hit.getSignalScore(),
                hit.getTimeScore(),
                hit.getEmbeddingModel(),
                hit.getSourceType(),
                hit.getTier(),
                hit.getEnv(),
                hit.getChunkScore(),
                hit.getChunkCreatedAt() == null ? null : hit.getChunkCreatedAt().toEpochMilli(),
                now);
    }

    private String paramsJson(RecallStats stats) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("includeArchived", stats.isIncludeArchived());
        params.put("crossSourceEnabled", stats.isCrossSourceEnabled());
        params.put("halfLifeDays", stats.getHalfLifeDays());
        params.put("minVectorScore", stats.getMinVectorScore());
        params.put("minScore", stats.getMinScore());
        params.put("minScoreRatio", stats.getMinScoreRatio());
        params.put("vectorWeight", stats.getVectorWeight());
        params.put("signalWeight", stats.getSignalWeight());
        params.put("timeDecayWeight", stats.getTimeDecayWeight());
        try {
            return MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String truncate(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= maxLength ? raw : raw.substring(0, maxLength);
    }

    private static String truncateToEmpty(String raw, int maxLength) {
        String truncated = truncate(raw, maxLength);
        return truncated == null ? "" : truncated;
    }
}
