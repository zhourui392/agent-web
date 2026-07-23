package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.refinery.DiscardedRefineRecord;
import com.example.agentweb.domain.refinery.DiscardedRefineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
    public boolean deleteById(String id) {
        int rows = jdbc.update("DELETE FROM chat_rag_discarded WHERE id = ?", id);
        if (rows > 0) {
            log.info("refinery-discarded-deleted id={}", id);
        }
        return rows > 0;
    }
}
