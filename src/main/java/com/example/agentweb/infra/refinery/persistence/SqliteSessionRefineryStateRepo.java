package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.refinery.SessionRefineryState;
import com.example.agentweb.domain.refinery.SessionRefineryStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link SessionRefineryStateRepository} 的 SQLite 实现.
 *
 * <p>每个 session 至多一行, 用 SQLite 的 INSERT ... ON CONFLICT(session_id) DO UPDATE 实现 UPSERT.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Repository
@Slf4j
public class SqliteSessionRefineryStateRepo implements SessionRefineryStateRepository {

    private static final String COLUMNS =
            "session_id, last_refined_at, last_message_at_seen, last_chunk_id, last_error, retry_count";

    private final JdbcTemplate jdbc;

    public SqliteSessionRefineryStateRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(SessionRefineryState state) {
        int rows = jdbc.update(
                "INSERT INTO chat_session_rag_state (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET "
                        + "last_refined_at = excluded.last_refined_at, "
                        + "last_message_at_seen = excluded.last_message_at_seen, "
                        + "last_chunk_id = excluded.last_chunk_id, "
                        + "last_error = excluded.last_error, "
                        + "retry_count = excluded.retry_count",
                state.getSessionId(),
                state.getLastRefinedAt().toEpochMilli(),
                state.getLastMessageAtSeen().toEpochMilli(),
                state.getLastChunkId(),
                state.getLastError(),
                state.getRetryCount()
        );
        log.debug("refinery-state-saved sessionId={} affectedRows={}", state.getSessionId(), rows);
    }

    @Override
    public Optional<SessionRefineryState> findBySessionId(String sessionId) {
        List<SessionRefineryState> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_session_rag_state WHERE session_id = ?",
                ROW_MAPPER,
                sessionId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        int rows = jdbc.update(
                "DELETE FROM chat_session_rag_state WHERE session_id = ?", sessionId);
        log.debug("refinery-state-deleted sessionId={} affectedRows={}", sessionId, rows);
    }

    private static final RowMapper<SessionRefineryState> ROW_MAPPER = (rs, rowNum) -> new SessionRefineryState(
            rs.getString("session_id"),
            Instant.ofEpochMilli(rs.getLong("last_refined_at")),
            Instant.ofEpochMilli(rs.getLong("last_message_at_seen")),
            rs.getString("last_chunk_id"),
            rs.getString("last_error"),
            rs.getInt("retry_count")
    );
}
