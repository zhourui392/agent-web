package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.refinery.ArchiveReason;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TtlCategory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link RagChunkRepository} 的 SQLite 实现.
 *
 * <p>embedding 列存 BLOB (float[] 小端序列化, 见 {@link EmbeddingCodec});
 * trigger_signals 列存 JSON 数组文本; 时间戳存 epoch millis.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Repository
@Slf4j
public class SqliteRagChunkRepo implements RagChunkRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() {
    };

    private static final String COLUMNS =
            "id, source_session_id, source_msg_range, title, trigger_signals, trigger_description, "
                    + "context, process, conclusion, ttl_category, score, created_at, expires_at, "
                    + "archived_at, agent_type, embedding_model, embedding, source_type, tier, env, "
                    + "detail_path";

    /** "可召回"判定: 未归档且未过期. findActive / findPage(activeOnly) / count(activeOnly) 共用, 防口径漂移. */
    private static final String ACTIVE_WHERE =
            "archived_at IS NULL AND (expires_at IS NULL OR expires_at > ?)";

    private final JdbcTemplate jdbc;

    public SqliteRagChunkRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(RagChunk chunk) {
        int rows = jdbc.update(
                "INSERT OR IGNORE INTO chat_rag_chunk (" + COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chunk.getId(),
                chunk.getSourceSessionId(),
                chunk.getSourceMsgRange(),
                chunk.getContent().getTitle(),
                serializeSignals(chunk.getContent().getTriggerSignals()),
                chunk.getContent().getTriggerDescription(),
                chunk.getContent().getContext(),
                chunk.getContent().getProcess(),
                chunk.getContent().getConclusion(),
                chunk.getTtlCategory().name(),
                chunk.getScore(),
                chunk.getCreatedAt().toEpochMilli(),
                toEpochOrNull(chunk.getExpiresAt()),
                toEpochOrNull(chunk.getArchivedAt()),
                chunk.getAgentType().name(),
                chunk.getEmbeddingModel(),
                EmbeddingCodec.encode(chunk.getEmbedding()),
                chunk.getSourceType().name(),
                chunk.getTier().name(),
                chunk.getEnv(),
                chunk.getDetailPath()
        );
        log.debug("refinery-chunk-saved id={} session={} sourceType={} tier={} score={} affectedRows={}",
                chunk.getId(), chunk.getSourceSessionId(),
                chunk.getSourceType(), chunk.getTier(), chunk.getScore(), rows);
    }

    @Override
    public Optional<RagChunk> findById(String id) {
        List<RagChunk> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_rag_chunk WHERE id = ?",
                ROW_MAPPER,
                id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<RagChunk> findActive(Instant now) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_rag_chunk WHERE " + ACTIVE_WHERE,
                ROW_MAPPER,
                now.toEpochMilli());
    }

    @Override
    public List<RagChunk> findPage(boolean activeOnly, Instant now, int offset, int limit) {
        if (activeOnly) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM chat_rag_chunk WHERE " + ACTIVE_WHERE
                            + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    ROW_MAPPER,
                    now.toEpochMilli(), limit, offset);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_rag_chunk "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER,
                limit, offset);
    }

    @Override
    public long count(boolean activeOnly, Instant now) {
        Long n = activeOnly
                ? jdbc.queryForObject(
                        "SELECT COUNT(*) FROM chat_rag_chunk WHERE " + ACTIVE_WHERE,
                        Long.class, now.toEpochMilli())
                : jdbc.queryForObject("SELECT COUNT(*) FROM chat_rag_chunk", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public boolean markArchived(String id, Instant when, ArchiveReason reason) {
        int rows = jdbc.update(
                "UPDATE chat_rag_chunk SET archived_at = ?, archive_reason = ? "
                        + "WHERE id = ? AND archived_at IS NULL",
                when.toEpochMilli(), reason == null ? null : reason.name(), id);
        return rows > 0;
    }

    @Override
    public int archiveExpiredBefore(Instant cutoff) {
        long ms = cutoff.toEpochMilli();
        int rows = jdbc.update(
                "UPDATE chat_rag_chunk SET archived_at = ?, archive_reason = 'TTL_EXPIRED' "
                        + "WHERE archived_at IS NULL AND expires_at IS NOT NULL AND expires_at <= ?",
                ms, ms);
        if (rows > 0) {
            log.info("refinery-archived-expired count={} cutoff={}", rows, cutoff);
        }
        return rows;
    }

    @Override
    public boolean updateTier(String chunkId, TrustTier tier) {
        int rows = jdbc.update(
                "UPDATE chat_rag_chunk SET tier = ? WHERE id = ?",
                tier.name(), chunkId);
        if (rows > 0) {
            log.info("refinery-chunk-tier-updated id={} tier={}", chunkId, tier);
        }
        return rows > 0;
    }

    @Override
    public int deleteBySourceSessionId(String sessionId) {
        int rows = jdbc.update(
                "DELETE FROM chat_rag_chunk WHERE source_session_id = ?", sessionId);
        if (rows > 0) {
            log.info("refinery-chunk-deleted session={} count={}", sessionId, rows);
        }
        return rows;
    }

    @Override
    public Map<SourceType, Integer> countActiveBySourceType() {
        Map<SourceType, Integer> result = new EnumMap<>(SourceType.class);
        jdbc.query(
                "SELECT source_type, COUNT(*) AS cnt FROM chat_rag_chunk WHERE archived_at IS NULL GROUP BY source_type",
                (rs) -> {
                    result.put(SourceType.valueOf(rs.getString("source_type")), rs.getInt("cnt"));
                });
        return result;
    }

    @Override
    public Map<TrustTier, Integer> countActiveByTier() {
        Map<TrustTier, Integer> result = new EnumMap<>(TrustTier.class);
        jdbc.query(
                "SELECT tier, COUNT(*) AS cnt FROM chat_rag_chunk WHERE archived_at IS NULL GROUP BY tier",
                (rs) -> {
                    result.put(TrustTier.valueOf(rs.getString("tier")), rs.getInt("cnt"));
                });
        return result;
    }

    @Override
    public int countArchived() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_rag_chunk WHERE archived_at IS NOT NULL", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public boolean deleteById(String id) {
        int rows = jdbc.update("DELETE FROM chat_rag_chunk WHERE id = ?", id);
        if (rows > 0) {
            log.info("refinery-chunk-deleted-by-id id={}", id);
        }
        return rows > 0;
    }

    private static String serializeSignals(List<String> signals) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(signals);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
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

    private static Long toEpochOrNull(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    @Override
    public int incrementInjectCount(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        return jdbc.update("UPDATE chat_rag_chunk SET inject_count = inject_count + 1 WHERE id IN ("
                + placeholders + ")", chunkIds.toArray());
    }

    @Override
    public boolean incrementAdoptCount(String chunkId) {
        return jdbc.update("UPDATE chat_rag_chunk SET adopt_count = adopt_count + 1 WHERE id = ?",
                chunkId) > 0;
    }

    @Override
    public boolean updateEmbedding(String chunkId, float[] embedding, String embeddingModel) {
        return jdbc.update("UPDATE chat_rag_chunk SET embedding = ?, embedding_model = ? WHERE id = ?",
                EmbeddingCodec.encode(embedding), embeddingModel, chunkId) > 0;
    }

    private static Instant fromEpochOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static final RowMapper<RagChunk> ROW_MAPPER = (rs, rowNum) -> {
        RefinedContent content = new RefinedContent(
                rs.getString("title"),
                deserializeSignals(rs.getString("trigger_signals")),
                rs.getString("trigger_description"),
                rs.getString("context"),
                rs.getString("process"),
                rs.getString("conclusion"));
        return RagChunk.builder()
                .id(rs.getString("id"))
                .sourceSessionId(rs.getString("source_session_id"))
                .sourceMsgRange(rs.getString("source_msg_range"))
                .agentType(AgentType.valueOf(rs.getString("agent_type")))
                .content(content)
                .score(rs.getDouble("score"))
                .ttlCategory(TtlCategory.valueOf(rs.getString("ttl_category")))
                .createdAt(Instant.ofEpochMilli(rs.getLong("created_at")))
                .expiresAt(fromEpochOrNull(rs, "expires_at"))
                .archivedAt(fromEpochOrNull(rs, "archived_at"))
                .embeddingModel(rs.getString("embedding_model"))
                .embedding(EmbeddingCodec.decode(rs.getBytes("embedding")))
                .sourceType(SourceType.valueOf(rs.getString("source_type")))
                .tier(TrustTier.valueOf(rs.getString("tier")))
                .env(rs.getString("env"))
                .detailPath(rs.getString("detail_path"))
                .build();
    };
}
