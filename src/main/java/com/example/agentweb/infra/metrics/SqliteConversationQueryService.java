package com.example.agentweb.infra.metrics;

import com.example.agentweb.app.metrics.ConversationDetail;
import com.example.agentweb.app.metrics.ConversationMessage;
import com.example.agentweb.app.metrics.ConversationPage;
import com.example.agentweb.app.metrics.ConversationQueryService;
import com.example.agentweb.app.metrics.ConversationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@link ConversationQueryService} 的 SQLite 实现:对 chat_session / chat_message 做只读投影。
 *
 * <p>读侧:不经聚合根、不返回半截聚合;admin 全量视角,刻意不拼 user_id 隔离条件。
 * 关键字走 LIKE,标题为空时由首条 user 消息兜底。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
public class SqliteConversationQueryService implements ConversationQueryService {

    /**
     * 展示标题表达式:真实 title 优先,为空时回退首条 user 消息。
     * SELECT 投影与关键字 WHERE 共用同一表达式,保证「搜得到 = 看得到」。
     */
    private static final String DISPLAY_TITLE_EXPR =
            "COALESCE(s.title, (SELECT m.content FROM chat_message m "
                    + "WHERE m.session_id = s.id AND m.role = 'user' ORDER BY m.id ASC LIMIT 1))";

    /** 列表行投影:展示标题 COALESCE(title, 首条 user 消息),并子查询消息数。 */
    private static final String LIST_SELECT =
            "SELECT s.id, s.agent_type, s.user_id, s.user_name, s.client_ip, s.created_at, "
                    + "s.last_message_at, s.feedback_rating, "
                    + "(SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count, "
                    + DISPLAY_TITLE_EXPR + " AS display_title "
                    + "FROM chat_session s";

    private static final RowMapper<ConversationRecord> RECORD_MAPPER = (rs, rowNum) -> mapRecord(rs);

    private static final RowMapper<ConversationMessage> MESSAGE_MAPPER = (rs, rowNum) -> {
        ConversationMessage m = new ConversationMessage();
        m.setRole(rs.getString("role"));
        m.setContent(rs.getString("content"));
        m.setTimestamp(rs.getString("timestamp"));
        return m;
    };

    private final JdbcTemplate jdbc;

    public SqliteConversationQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConversationPage list(int page, int size, String keyword) {
        String like = StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null;
        String where = like == null ? ""
                : " WHERE (" + DISPLAY_TITLE_EXPR + " LIKE ? OR s.user_name LIKE ? OR s.user_id LIKE ?)";

        long total = countTotal(where, like);
        int offset = (page - 1) * size;
        String sql = LIST_SELECT + where + " ORDER BY s.created_at DESC LIMIT ? OFFSET ?";
        List<ConversationRecord> rows = like == null
                ? jdbc.query(sql, RECORD_MAPPER, size, offset)
                : jdbc.query(sql, RECORD_MAPPER, like, like, like, size, offset);
        return new ConversationPage(rows, total, page, size);
    }

    @Override
    public ConversationDetail detail(String sessionId) {
        List<ConversationRecord> records = jdbc.query(
                LIST_SELECT + " WHERE s.id = ?", RECORD_MAPPER, sessionId);
        if (records.isEmpty()) {
            return null;
        }
        List<ConversationMessage> messages = jdbc.query(
                "SELECT role, content, timestamp FROM chat_message WHERE session_id = ? ORDER BY id ASC",
                MESSAGE_MAPPER, sessionId);
        ConversationDetail detail = new ConversationDetail();
        detail.setRecord(records.get(0));
        detail.setMessages(messages);
        return detail;
    }

    private long countTotal(String where, String like) {
        String sql = "SELECT COUNT(*) FROM chat_session s" + where;
        Long total = like == null
                ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, like, like, like);
        return total == null ? 0L : total;
    }

    private static ConversationRecord mapRecord(ResultSet rs) throws SQLException {
        ConversationRecord r = new ConversationRecord();
        r.setSessionId(rs.getString("id"));
        r.setAgentType(rs.getString("agent_type"));
        r.setTitle(rs.getString("display_title"));
        r.setUserId(rs.getString("user_id"));
        r.setUserName(rs.getString("user_name"));
        r.setClientIp(rs.getString("client_ip"));
        r.setMessageCount(rs.getLong("message_count"));
        r.setCreatedAt(rs.getString("created_at"));
        long lastMessageAt = rs.getLong("last_message_at");
        r.setLastMessageAt(rs.wasNull() ? null : lastMessageAt);
        r.setFeedbackRating(rs.getString("feedback_rating"));
        return r;
    }
}
