package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.ManualSession;
import com.example.agentweb.domain.auth.ManualSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * {@link ManualSessionRepository} 的 SQLite 实现, 对应表 {@code manual_session}。
 *
 * <p>save 走 SQLite 的 {@code INSERT ... ON CONFLICT(session_id) DO UPDATE} upsert,
 * 单条 sessionId 可重复保存 (理论上聚合根 sessionId 全局唯一, 实际不会冲突,
 * 此处只是防御性 upsert)。过期清理由调用方在后台 tick 中触发。</p>
 *
 * @author zhourui(V33215020)
 */
@Repository
@Slf4j
public class SqliteManualSessionRepo implements ManualSessionRepository {

    private final JdbcTemplate jdbc;

    public SqliteManualSessionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ManualSession> ROW_MAPPER = new RowMapper<ManualSession>() {
        @Override
        public ManualSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            return ManualSession.restore(
                    rs.getString("session_id"),
                    rs.getString("user_id"),
                    rs.getString("user_name"),
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("expires_at"))
            );
        }
    };

    @Override
    public void save(ManualSession session) {
        int rows = jdbc.update(
                "INSERT INTO manual_session (session_id, user_id, user_name, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT(session_id) DO UPDATE SET"
                        + " user_id = excluded.user_id,"
                        + " user_name = excluded.user_name,"
                        + " created_at = excluded.created_at,"
                        + " expires_at = excluded.expires_at",
                session.getSessionId(),
                session.getUserId(),
                session.getUserName(),
                session.getCreatedAt().toEpochMilli(),
                session.getExpiresAt().toEpochMilli()
        );
        log.debug("manual-session-saved userId={} affectedRows={}", session.getUserId(), rows);
    }

    @Override
    public Optional<ManualSession> findById(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }
        try {
            ManualSession s = jdbc.queryForObject(
                    "SELECT session_id, user_id, user_name, created_at, expires_at"
                            + " FROM manual_session WHERE session_id = ?",
                    ROW_MAPPER, sessionId);
            return Optional.ofNullable(s);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void deleteById(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        int rows = jdbc.update("DELETE FROM manual_session WHERE session_id = ?", sessionId);
        log.debug("manual-session-deleted affectedRows={}", rows);
    }

    @Override
    public int deleteExpiredBefore(Instant threshold) {
        int rows = jdbc.update("DELETE FROM manual_session WHERE expires_at < ?",
                threshold.toEpochMilli());
        if (rows > 0) {
            log.info("manual-session-purge-expired removedRows={}", rows);
        }
        return rows;
    }
}
