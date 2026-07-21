package com.example.agentweb.infra;

import com.example.agentweb.app.ChatMessageView;
import com.example.agentweb.app.ChatSessionQueryService;
import com.example.agentweb.app.ChatSessionSummary;
import com.example.agentweb.app.SharedSessionView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话读模型 SQLite 实现。摘要/消息回放与写侧 {@link SqliteSessionRepo} 相同库表，
 * 用户隔离口径与写侧 findById 完全一致（{@code user_id IS NULL OR user_id = ?}）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Component
public class SqliteChatSessionQueryService implements ChatSessionQueryService {

    /** 摘要标题超长截断阈值，与历史 Map 投影行为保持一致。 */
    private static final int TITLE_MAX_CHARS = 50;

    private static final RowMapper<ChatSessionSummary> SUMMARY_MAPPER = (rs, rowNum) -> {
        String title = rs.getString("title");
        String normalizedTitle = title == null
                ? "新对话"
                : (title.length() > TITLE_MAX_CHARS ? title.substring(0, TITLE_MAX_CHARS) + "..." : title);
        return new ChatSessionSummary(
                rs.getString("id"),
                rs.getString("agent_type"),
                rs.getString("working_dir"),
                rs.getString("created_at"),
                rs.getString("resume_id"),
                rs.getString("env"),
                rs.getInt("message_count"),
                rs.getString("user_id"),
                normalizedTitle);
    };

    private static final RowMapper<ChatMessageView> MESSAGE_MAPPER = (rs, rowNum) -> new ChatMessageView(
            rs.getLong("id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("timestamp"),
            rs.getString("recall_json"));

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUserProvider;

    public SqliteChatSessionQueryService(JdbcTemplate jdbc, CurrentUserProvider currentUserProvider) {
        this.jdbc = jdbc;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public List<ChatSessionSummary> findSummaryPaged(int offset, int limit) {
        boolean filter = currentUserProvider.shouldFilter();
        String where = filter ? " WHERE (s.user_id IS NULL OR s.user_id = ?)" : "";
        String sql = "SELECT s.id, s.agent_type, s.working_dir, s.created_at, s.resume_id, s.env, s.user_id, "
                + "  (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count, "
                + "  COALESCE(s.title, (SELECT m.content FROM chat_message m WHERE m.session_id = s.id "
                + "    AND m.role = 'user' ORDER BY m.id ASC LIMIT 1)) AS title "
                + "FROM chat_session s" + where + " ORDER BY s.created_at DESC LIMIT ? OFFSET ?";
        Object[] args = filter
                ? new Object[]{currentUserProvider.currentUserId(), limit, offset}
                : new Object[]{limit, offset};
        return jdbc.query(sql, SUMMARY_MAPPER, args);
    }

    @Override
    public List<ChatMessageView> findMessageViews(String sessionId) {
        if (!sessionVisible(sessionId)) {
            return null;
        }
        return loadMessages(sessionId);
    }

    @Override
    public SharedSessionView findSharedView(String shareToken) {
        List<SharedSessionView> heads = jdbc.query(
                "SELECT id, agent_type, working_dir, created_at, title "
                        + "FROM chat_session WHERE share_token = ?",
                (rs, rowNum) -> new SharedSessionView(
                        rs.getString("title"),
                        rs.getString("agent_type"),
                        rs.getString("working_dir"),
                        rs.getString("created_at"),
                        loadMessages(rs.getString("id"))),
                shareToken);
        return heads.isEmpty() ? null : heads.get(0);
    }

    private List<ChatMessageView> loadMessages(String sessionId) {
        return jdbc.query(
                "SELECT m.id, m.role, m.content, m.timestamp, r.payload_json AS recall_json "
                        + "FROM chat_message m "
                        + "LEFT JOIN chat_message_recall r ON r.message_id = m.id "
                        + "WHERE m.session_id = ? ORDER BY m.id ASC",
                MESSAGE_MAPPER,
                sessionId);
    }

    /** 会话存在性 + 用户隔离可见性检查，口径与写侧 findById 一致。 */
    private boolean sessionVisible(String sessionId) {
        boolean filter = currentUserProvider.shouldFilter();
        String sql = "SELECT COUNT(*) FROM chat_session WHERE id = ?"
                + (filter ? " AND (user_id IS NULL OR user_id = ?)" : "");
        Object[] args = filter
                ? new Object[]{sessionId, currentUserProvider.currentUserId()}
                : new Object[]{sessionId};
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }
}
