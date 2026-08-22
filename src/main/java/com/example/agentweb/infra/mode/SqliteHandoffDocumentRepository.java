package com.example.agentweb.infra.mode;

import com.example.agentweb.domain.mode.DuplicateHandoffException;
import com.example.agentweb.domain.mode.HandoffDocument;
import com.example.agentweb.domain.mode.HandoffDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * HandoffDocument 的 SQLite 仓储。
 *
 * <p>并发重复提交由 (from_session_id, idempotency_key) 唯一索引兜底，
 * 冲突翻译为领域异常 {@link DuplicateHandoffException} 供应用层幂等回读。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
@Repository
@Slf4j
public class SqliteHandoffDocumentRepository implements HandoffDocumentRepository {

    private final JdbcTemplate jdbc;

    public SqliteHandoffDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public HandoffDocument save(HandoffDocument document) {
        try {
            jdbc.update("INSERT INTO handoff_document (id, from_session_id, to_session_id, "
                            + "from_mode_id, to_mode_id, file_path, idempotency_key, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    document.getId(), document.getFromSessionId(), document.getToSessionId(),
                    document.getFromModeId(), document.getToModeId(),
                    document.getFilePath(), document.getIdempotencyKey(),
                    document.getCreatedAt().toString());
        } catch (DataAccessException ex) {
            // SQLite 驱动的唯一约束冲突常未被 Spring 翻译为 DataIntegrityViolation，按消息识别
            if (ex.getMessage() != null && ex.getMessage().contains("UNIQUE")) {
                throw new DuplicateHandoffException(
                        "handoff already exists for session " + document.getFromSessionId());
            }
            throw ex;
        }
        log.info("handoff-document-saved handoffId={} fromSession={} toSession={}",
                document.getId(), document.getFromSessionId(), document.getToSessionId());
        return document;
    }

    @Override
    public Optional<HandoffDocument> find(String id) {
        return queryOne("WHERE id = ?", id);
    }

    @Override
    public Optional<HandoffDocument> findByFromSessionAndIdempotencyKey(
            String fromSessionId, String idempotencyKey) {
        return queryOne("WHERE from_session_id = ? AND idempotency_key = ?",
                fromSessionId, idempotencyKey);
    }

    private Optional<HandoffDocument> queryOne(String where, Object... args) {
        try {
            List<HandoffDocument> rows = jdbc.query(
                    "SELECT id, from_session_id, to_session_id, from_mode_id, to_mode_id, "
                            + "file_path, idempotency_key, created_at FROM handoff_document " + where,
                    (rs, n) -> new HandoffDocument(
                            rs.getString("id"), rs.getString("from_session_id"),
                            rs.getString("to_session_id"), rs.getString("from_mode_id"),
                            rs.getString("to_mode_id"), rs.getString("file_path"),
                            rs.getString("idempotency_key"),
                            java.time.Instant.parse(rs.getString("created_at"))),
                    args);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
