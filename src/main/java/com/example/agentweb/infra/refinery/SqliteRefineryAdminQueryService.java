package com.example.agentweb.infra.refinery;

import com.example.agentweb.app.refinery.DiscardedRefinePage;
import com.example.agentweb.app.refinery.DiscardedRefineView;
import com.example.agentweb.app.refinery.RefineryAdminQueryService;
import com.example.agentweb.app.refinery.RefineryChunkPage;
import com.example.agentweb.app.refinery.RefineryChunkView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Refinery 管理端 SQLite 读模型实现，直接投影管理视图而不恢复聚合根。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
public class SqliteRefineryAdminQueryService implements RefineryAdminQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String FILTER_ACTIVE = "active";
    private static final String ACTIVE_WHERE =
            "archived_at IS NULL AND (expires_at IS NULL OR expires_at > ?)";
    private static final String CHUNK_COLUMNS =
            "id, title, score, ttl_category, conclusion, trigger_signals, source_session_id, "
                    + "source_msg_range, agent_type, created_at, expires_at, archived_at";
    private static final String DISCARDED_COLUMNS =
            "id, title, score, threshold, ttl_category, conclusion, source_type, "
                    + "source_session_id, agent_type, env, created_at, reason";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() {
    };

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SqliteRefineryAdminQueryService(
            JdbcTemplate jdbc,
            @Qualifier("chatRagClock") Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public RefineryChunkPage findChunks(int page, int size, String status) {
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);
        long offset = (long) (safePage - 1) * safeSize;
        Instant now = clock.instant();
        boolean activeOnly = FILTER_ACTIVE.equalsIgnoreCase(status);
        List<RefineryChunkView> items;
        Long total;
        if (activeOnly) {
            items = jdbc.query(
                    "SELECT " + CHUNK_COLUMNS + " FROM chat_rag_chunk WHERE " + ACTIVE_WHERE
                            + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> mapChunk(rs, now),
                    now.toEpochMilli(), safeSize, offset);
            total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_rag_chunk WHERE " + ACTIVE_WHERE,
                    Long.class, now.toEpochMilli());
        } else {
            items = jdbc.query(
                    "SELECT " + CHUNK_COLUMNS + " FROM chat_rag_chunk "
                            + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> mapChunk(rs, now),
                    safeSize, offset);
            total = jdbc.queryForObject("SELECT COUNT(*) FROM chat_rag_chunk", Long.class);
        }
        return new RefineryChunkPage(items, Objects.requireNonNull(total), safePage, safeSize);
    }

    @Override
    public DiscardedRefinePage findDiscarded(int page, int size) {
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);
        long offset = (long) (safePage - 1) * safeSize;
        List<DiscardedRefineView> items = jdbc.query(
                "SELECT " + DISCARDED_COLUMNS + " FROM chat_rag_discarded "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapDiscarded(rs),
                safeSize, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM chat_rag_discarded", Long.class);
        return new DiscardedRefinePage(items, Objects.requireNonNull(total), safePage, safeSize);
    }

    private static RefineryChunkView mapChunk(ResultSet rs, Instant now) throws SQLException {
        Long expiresAt = readNullableEpoch(rs, "expires_at");
        Long archivedAt = readNullableEpoch(rs, "archived_at");
        boolean expired = expiresAt != null && expiresAt <= now.toEpochMilli();
        String status = archivedAt != null || expired
                ? RefineryChunkView.STATUS_ARCHIVED
                : RefineryChunkView.STATUS_ACTIVE;
        return new RefineryChunkView(
                rs.getString("id"),
                rs.getString("title"),
                rs.getDouble("score"),
                rs.getString("ttl_category"),
                rs.getString("conclusion"),
                deserializeSignals(rs.getString("trigger_signals")),
                rs.getString("source_session_id"),
                rs.getString("source_msg_range"),
                rs.getString("agent_type"),
                Instant.ofEpochMilli(rs.getLong("created_at")).toString(),
                toInstantString(expiresAt),
                toInstantString(archivedAt),
                status);
    }

    private static DiscardedRefineView mapDiscarded(ResultSet rs) throws SQLException {
        String ttlCategory = rs.getString("ttl_category");
        return new DiscardedRefineView(
                rs.getString("id"),
                rs.getString("title"),
                rs.getDouble("score"),
                rs.getDouble("threshold"),
                rs.wasNull() ? null : ttlCategory,
                rs.getString("conclusion"),
                rs.getString("source_type"),
                rs.getString("source_session_id"),
                rs.getString("agent_type"),
                rs.getString("env"),
                Instant.ofEpochMilli(rs.getLong("created_at")).toString(),
                rs.getString("reason"),
                DiscardedRefineView.STATUS_DISCARDED);
    }

    private static int normalizePage(int page) {
        return Math.max(1, page);
    }

    private static int normalizeSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    private static Long readNullableEpoch(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String toInstantString(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString();
    }

    private static List<String> deserializeSignals(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
