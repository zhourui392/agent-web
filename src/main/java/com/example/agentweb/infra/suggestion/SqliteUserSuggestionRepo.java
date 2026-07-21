package com.example.agentweb.infra.suggestion;

import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.domain.suggestion.UserSuggestionPage;
import com.example.agentweb.domain.suggestion.UserSuggestionRepository;
import com.example.agentweb.domain.suggestion.UserSuggestionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link UserSuggestionRepository} 的 SQLite 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Repository
@Slf4j
public class SqliteUserSuggestionRepo implements UserSuggestionRepository {

    private static final String COLUMNS = "id, user_id, user_name, title, content, contact, status, "
            + "admin_reply, created_at, updated_at, replied_at";

    private static final RowMapper<UserSuggestion> ROW_MAPPER = new RowMapper<UserSuggestion>() {
        @Override
        public UserSuggestion mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new UserSuggestion(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("user_name"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getString("contact"),
                    UserSuggestionStatus.valueOf(rs.getString("status")),
                    rs.getString("admin_reply"),
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("updated_at")),
                    instantOrNull(rs, "replied_at"));
        }
    };

    private final JdbcTemplate jdbc;

    public SqliteUserSuggestionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(UserSuggestion suggestion) {
        int rows = jdbc.update("INSERT OR REPLACE INTO user_suggestion ("
                        + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                suggestion.getId(),
                suggestion.getUserId(),
                suggestion.getUserName(),
                suggestion.getTitle(),
                suggestion.getContent(),
                suggestion.getContact(),
                suggestion.getStatus().name(),
                suggestion.getAdminReply(),
                suggestion.getCreatedAt().toEpochMilli(),
                suggestion.getUpdatedAt().toEpochMilli(),
                suggestion.getRepliedAt() == null ? null : suggestion.getRepliedAt().toEpochMilli());
        log.debug("user-suggestion-saved id={} status={} affectedRows={}",
                suggestion.getId(), suggestion.getStatus(), rows);
    }

    @Override
    public UserSuggestion findById(String id) {
        List<UserSuggestion> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM user_suggestion WHERE id = ?",
                ROW_MAPPER,
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<UserSuggestion> findByUserId(String userId, int limit) {
        if (userId == null || userId.trim().isEmpty()) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM user_suggestion "
                            + "WHERE user_id IS NULL ORDER BY updated_at DESC LIMIT ?",
                    ROW_MAPPER,
                    limit);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM user_suggestion "
                        + "WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?",
                ROW_MAPPER,
                userId.trim(),
                limit);
    }

    @Override
    public UserSuggestionPage findPage(UserSuggestionStatus status, String keyword, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(status, keyword, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM user_suggestion" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<UserSuggestion> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM user_suggestion" + where
                        + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER,
                pageArgs.toArray());
        return new UserSuggestionPage(rows, total == null ? 0L : total, page, size);
    }

    private String buildWhere(UserSuggestionStatus status, String keyword, List<Object> args) {
        StringBuilder where = new StringBuilder();
        if (status != null) {
            appendWherePrefix(where);
            where.append("status = ?");
            args.add(status.name());
        }
        String kw = normalizeKeyword(keyword);
        if (kw != null) {
            appendWherePrefix(where);
            where.append("(title LIKE ? OR content LIKE ? OR user_id LIKE ? OR user_name LIKE ?)");
            String like = "%" + kw + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return where.toString();
    }

    private void appendWherePrefix(StringBuilder where) {
        if (where.length() == 0) {
            where.append(" WHERE ");
        } else {
            where.append(" AND ");
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }
        return keyword.trim();
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
