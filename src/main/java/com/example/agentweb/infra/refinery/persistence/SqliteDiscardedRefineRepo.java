package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.refinery.DiscardedRefineRecord;
import com.example.agentweb.domain.refinery.DiscardedRefineRepository;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TtlCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * {@link DiscardedRefineRepository} 的 SQLite 实现. 镜像 {@link SqliteRagChunkRepo} 的
 * COLUMNS / ROW_MAPPER / 倒序分页结构.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-04
 */
@Repository
@Slf4j
public class SqliteDiscardedRefineRepo implements DiscardedRefineRepository {

    private static final String COLUMNS =
            "id, source_type, source_session_id, title, conclusion, ttl_category, "
                    + "score, threshold, agent_type, env, created_at, reason";

    private final JdbcTemplate jdbc;

    public SqliteDiscardedRefineRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(DiscardedRefineRecord r) {
        int rows = jdbc.update(
                "INSERT INTO chat_rag_discarded (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.getId(),
                r.getSourceType().name(),
                r.getSourceSessionId(),
                r.getTitle(),
                r.getConclusion(),
                r.getTtlCategory() == null ? null : r.getTtlCategory().name(),
                r.getScore(),
                r.getThreshold(),
                r.getAgentType(),
                r.getEnv(),
                r.getCreatedAt().toEpochMilli(),
                r.getReason());
        log.debug("refinery-discarded-saved id={} session={} score={} affectedRows={}",
                r.getId(), r.getSourceSessionId(), r.getScore(), rows);
    }

    @Override
    public List<DiscardedRefineRecord> findPage(int offset, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_rag_discarded "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER,
                limit, offset);
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_rag_discarded", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public boolean deleteById(String id) {
        int rows = jdbc.update("DELETE FROM chat_rag_discarded WHERE id = ?", id);
        if (rows > 0) {
            log.info("refinery-discarded-deleted id={}", id);
        }
        return rows > 0;
    }

    private static final RowMapper<DiscardedRefineRecord> ROW_MAPPER = (rs, rowNum) -> DiscardedRefineRecord.builder()
            .id(rs.getString("id"))
            .sourceType(SourceType.valueOf(rs.getString("source_type")))
            .sourceSessionId(rs.getString("source_session_id"))
            .title(rs.getString("title"))
            .conclusion(readNullableString(rs, "conclusion"))
            .ttlCategory(readTtlCategory(rs))
            .score(rs.getDouble("score"))
            .threshold(rs.getDouble("threshold"))
            .agentType(readNullableString(rs, "agent_type"))
            .env(readNullableString(rs, "env"))
            .createdAt(Instant.ofEpochMilli(rs.getLong("created_at")))
            .reason(rs.getString("reason"))
            .build();

    private static TtlCategory readTtlCategory(ResultSet rs) throws SQLException {
        String v = rs.getString("ttl_category");
        return rs.wasNull() || v == null ? null : TtlCategory.valueOf(v);
    }

    private static String readNullableString(ResultSet rs, String column) throws SQLException {
        String v = rs.getString(column);
        return rs.wasNull() ? null : v;
    }
}
