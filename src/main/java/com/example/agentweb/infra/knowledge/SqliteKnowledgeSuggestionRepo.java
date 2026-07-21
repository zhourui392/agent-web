package com.example.agentweb.infra.knowledge;

import com.example.agentweb.app.knowledge.KnowledgeInboxQueryService;
import com.example.agentweb.app.knowledge.KnowledgeSuggestionView;
import com.example.agentweb.domain.knowledge.KnowledgeScope;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestionRepository;
import com.example.agentweb.domain.knowledge.SuggestionStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * knowledge_suggestion 表 SQLite 实现：写侧仓储 + 读侧收件箱投影一 bean 双接口
 * （对齐 SqliteDeliveryStore 先例）。触发词走 JSON 列（requirement participants_json 先例）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteKnowledgeSuggestionRepo
        implements KnowledgeSuggestionRepository, KnowledgeInboxQueryService {

    private static final String COLUMNS = "id, requirement_id, scope, source_ref, title, "
            + "trigger_signals_json, phenomenon, root_cause, solution, notes, status, "
            + "reject_reason, reviewed_by, reviewed_at, issue_id, issue_path, created_at";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final RowMapper<KnowledgeSuggestion> rowMapper = (rs, rowNum) -> mapRow(rs);

    public SqliteKnowledgeSuggestionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(KnowledgeSuggestion suggestion) {
        jdbc.update("INSERT INTO knowledge_suggestion (" + COLUMNS
                        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                suggestion.getId(),
                suggestion.getRequirementId(),
                suggestion.getScope().name(),
                suggestion.getSourceRef(),
                suggestion.getTitle(),
                toJson(suggestion.getTriggerSignals()),
                suggestion.getPhenomenon(),
                suggestion.getRootCause(),
                suggestion.getSolution(),
                suggestion.getNotes(),
                suggestion.getStatus().name(),
                suggestion.getRejectReason(),
                suggestion.getReviewedBy(),
                epochMilli(suggestion.getReviewedAt()),
                suggestion.getIssueId(),
                suggestion.getIssuePath(),
                suggestion.getCreatedAt().toEpochMilli());
    }

    @Override
    public void update(KnowledgeSuggestion suggestion) {
        jdbc.update("UPDATE knowledge_suggestion SET title=?, trigger_signals_json=?, phenomenon=?, "
                        + "root_cause=?, solution=?, notes=?, status=?, reject_reason=?, reviewed_by=?, "
                        + "reviewed_at=?, issue_id=?, issue_path=? WHERE id=?",
                suggestion.getTitle(),
                toJson(suggestion.getTriggerSignals()),
                suggestion.getPhenomenon(),
                suggestion.getRootCause(),
                suggestion.getSolution(),
                suggestion.getNotes(),
                suggestion.getStatus().name(),
                suggestion.getRejectReason(),
                suggestion.getReviewedBy(),
                epochMilli(suggestion.getReviewedAt()),
                suggestion.getIssueId(),
                suggestion.getIssuePath(),
                suggestion.getId());
    }

    @Override
    public KnowledgeSuggestion findById(String id) {
        List<KnowledgeSuggestion> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM knowledge_suggestion WHERE id=?", rowMapper, id);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<KnowledgeSuggestionView> listByStatus(String status, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM knowledge_suggestion WHERE status=? "
                        + "ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> toView(mapRow(rs)), status, limit);
    }

    @Override
    public boolean existsForRequirement(String requirementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM knowledge_suggestion WHERE requirement_id=?",
                Integer.class, requirementId);
        return count != null && count > 0;
    }

    private KnowledgeSuggestionView toView(KnowledgeSuggestion s) {
        return new KnowledgeSuggestionView(s.getId(), s.getRequirementId(), s.getScope().name(),
                s.getSourceRef(), s.getTitle(), s.getTriggerSignals(), s.getPhenomenon(),
                s.getRootCause(), s.getSolution(), s.getNotes(), s.getStatus().name(),
                s.getRejectReason(), s.getIssueId(), s.getCreatedAt().toEpochMilli());
    }

    private KnowledgeSuggestion mapRow(ResultSet rs) throws SQLException {
        long reviewedAt = rs.getLong("reviewed_at");
        boolean reviewedAtNull = rs.wasNull();
        return new KnowledgeSuggestion(
                rs.getString("id"),
                rs.getString("requirement_id"),
                KnowledgeScope.valueOf(rs.getString("scope")),
                rs.getString("source_ref"),
                rs.getString("title"),
                fromJson(rs.getString("trigger_signals_json")),
                rs.getString("phenomenon"),
                rs.getString("root_cause"),
                rs.getString("solution"),
                rs.getString("notes"),
                SuggestionStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                rs.getString("reviewed_by"),
                reviewedAtNull ? null : Instant.ofEpochMilli(reviewedAt),
                rs.getString("issue_id"),
                rs.getString("issue_path"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private String toJson(List<String> tokens) {
        try {
            return MAPPER.writeValueAsString(tokens);
        } catch (Exception e) {
            throw new IllegalStateException("trigger signals serialize failed", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("trigger signals deserialize failed", e);
        }
    }

    private Long epochMilli(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
