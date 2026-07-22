package com.example.agentweb.infra;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.chat.FeedbackRating;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * @author zhourui(V33215020)
 */
@Repository
@Slf4j
public class SqliteSessionRepo implements SessionRepository {

    /** 整行查询 chat_session 的列清单, 与 {@link #mapSession} 一一对应。 */
    private static final String SESSION_COLUMNS =
            "id, agent_type, working_dir, created_at, resume_id, title, env, "
                    + "feedback_rating, feedback_comment, feedback_at, client_ip, user_id, user_name";

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUserProvider;

    public SqliteSessionRepo(JdbcTemplate jdbc, CurrentUserProvider currentUserProvider) {
        this.jdbc = jdbc;
        this.currentUserProvider = currentUserProvider;
    }

    /** {@link #filterUserId()} 的"不过滤"哨兵(admin / 后台无上下文)，用 == 身份比较。 */
    private static final String NO_FILTER = new String("__NO_FILTER__");

    /**
     * 决定本次查询是否按 user_id 隔离：
     * 返回当前用户 ID 表示需过滤(WHERE 拼 {@code user_id IS NULL OR user_id = ?})；
     * 返回 {@link #NO_FILTER} 哨兵表示不过滤(admin 或后台无登录上下文，看全部)。
     */
    private String filterUserId() {
        return currentUserProvider.shouldFilter() ? currentUserProvider.currentUserId() : NO_FILTER;
    }

    @Override
    public void saveSession(ChatSession session) {
        int rows = jdbc.update(
                "INSERT OR IGNORE INTO chat_session (id, agent_type, working_dir, created_at, resume_id, title, env, last_message_at, client_ip, user_id, user_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                session.getId(),
                session.getAgentType().name(),
                session.getWorkingDir(),
                session.getCreatedAt().toString(),
                session.getResumeId(),
                session.getTitle(),
                session.getEnv(),
                session.getCreatedAt().toEpochMilli(),
                session.getClientIp(),
                session.getUserId(),
                session.getUserName()
        );
        log.debug("session-save sessionId={} affectedRows={}", session.getId(), rows);
    }

    @Override
    public void addMessage(String sessionId, ChatMessage message) {
        addMessageReturningId(sessionId, message);
    }

    @Override
    @Transactional
    public long addMessageReturningId(String sessionId, ChatMessage message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO chat_message (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, message.getRole());
            ps.setString(3, message.getContent());
            ps.setString(4, message.getTimestamp().toString());
            return ps;
        }, keyHolder);
        jdbc.update(
                "UPDATE chat_session SET last_message_at = ? WHERE id = ?",
                message.getTimestamp().toEpochMilli(),
                sessionId
        );
        Number key = keyHolder.getKey();
        long id = key == null ? -1L : key.longValue();
        log.debug("session-message-saved sessionId={} role={} id={} contentLen={}",
                sessionId, message.getRole(), id, message.getContent() == null ? 0 : message.getContent().length());
        return id;
    }

    @Override
    public void saveRecall(long messageId, String payloadJson) {
        jdbc.update(
                "INSERT OR REPLACE INTO chat_message_recall (message_id, payload_json) VALUES (?, ?)",
                messageId, payloadJson);
        log.debug("chat-recall-saved messageId={} payloadLen={}",
                messageId, payloadJson == null ? 0 : payloadJson.length());
    }

    @Override
    public ChatSession findById(String id) {
        // 数据隔离：普通用户仅能查自己的会话 + 老数据(user_id IS NULL)；admin / 无上下文(后台线程)不过滤。
        // 注意：本方法必须在请求线程的 fork 之前调用(streamMessage 已保证)，否则后台线程 uid=null 走 bypass 看全部。
        String filtered = filterUserId();
        String sql = "SELECT " + SESSION_COLUMNS + " FROM chat_session WHERE id = ?";
        Object[] args = filtered == NO_FILTER
                ? new Object[]{id}
                : new Object[]{id, filtered};
        if (filtered != NO_FILTER) {
            sql += " AND (user_id IS NULL OR user_id = ?)";
        }
        List<ChatSession> sessions = jdbc.query(sql, (rs, rowNum) -> mapSession(rs), args);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    @Override
    public List<ChatSession> findAll() {
        String filtered = filterUserId();
        String sql = "SELECT " + SESSION_COLUMNS + " FROM chat_session";
        if (filtered != NO_FILTER) {
            sql += " WHERE (user_id IS NULL OR user_id = ?)";
        }
        sql += " ORDER BY created_at DESC";
        Object[] args = filtered == NO_FILTER ? new Object[]{} : new Object[]{filtered};
        return jdbc.query(sql, (rs, rowNum) -> mapSession(rs), args);
    }

    @Override
    public void updateResumeId(String sessionId, String resumeId) {
        int rows = jdbc.update("UPDATE chat_session SET resume_id = ? WHERE id = ?", resumeId, sessionId);
        log.debug("session-resume-id-updated sessionId={} resumeId={} affectedRows={}",
                sessionId, resumeId, rows);
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        // 先清召回明细 (按 message_id 关联), 再删消息, 避免 chat_message_recall 残留孤儿行
        jdbc.update("DELETE FROM chat_message_recall WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE session_id = ?)", id);
        int msgRows = jdbc.update("DELETE FROM chat_message WHERE session_id = ?", id);
        int sessionRows = jdbc.update("DELETE FROM chat_session WHERE id = ?", id);
        log.info("session-deleted sessionId={} messageRows={} sessionRows={}", id, msgRows, sessionRows);
    }

    @Override
    @Transactional
    public int truncateFrom(String sessionId, long fromId) {
        // 同步清掉被截断消息的召回明细
        jdbc.update("DELETE FROM chat_message_recall WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE session_id = ? AND id >= ?)", sessionId, fromId);
        int deleted = jdbc.update(
                "DELETE FROM chat_message WHERE session_id = ? AND id >= ?",
                sessionId, fromId);
        jdbc.update("UPDATE chat_session SET resume_id = NULL WHERE id = ?", sessionId);
        return deleted;
    }

    @Override
    @Transactional
    public String setShareToken(String sessionId, String shareToken) {
        // If session already has a share token, return it
        List<String> existing = jdbc.query(
                "SELECT share_token FROM chat_session WHERE id = ? AND share_token IS NOT NULL",
                (rs, rowNum) -> rs.getString("share_token"),
                sessionId
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        jdbc.update("UPDATE chat_session SET share_token = ? WHERE id = ?", shareToken, sessionId);
        return shareToken;
    }

    @Override
    public ChatSession findByShareToken(String shareToken) {
        List<ChatSession> sessions = jdbc.query(
                "SELECT " + SESSION_COLUMNS + " FROM chat_session WHERE share_token = ?",
                (rs, rowNum) -> mapSession(rs),
                shareToken
        );
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    @Override
    public List<String> findIdsWithLastMessageBefore(long beforeMs, String belowThresholdSentinel,
                                                     int maxRetries, int limit) {
        // LEFT JOIN rag_state: 排除"已成功/below-threshold 且无新消息"与"真·失败已达重试上限"的会话,
        // 让 LIMIT 窗口只留给真正待处理者 (新会话 / 有新消息 / 可重试的失败), 避免旧会话钉死窗口。
        // 注意: last_message_at 在历史数据中可能是秒精度 (存量), rag_state.last_message_at_seen 为毫秒,
        // 故比较两侧统一 /1000 归一到秒, 否则旧会话会被误判为"有新消息"而永远入选 (与 RefineryAppServiceImpl
        // 的 shouldSkip 秒级比较保持一致)。
        return jdbc.query(
                "SELECT s.id FROM chat_session s "
                        + "LEFT JOIN chat_session_rag_state r ON r.session_id = s.id "
                        + "WHERE s.last_message_at IS NOT NULL AND s.last_message_at < ? "
                        + "AND ("
                        + "  r.session_id IS NULL "                                       // 从未评过
                        + "  OR r.last_message_at_seen / 1000 <> s.last_message_at / 1000 " // 有新消息 (秒级)
                        + "  OR (r.last_error IS NOT NULL "                               // 上次失败
                        + "      AND r.last_error <> ? "                                  // 但非 below-threshold (不重试)
                        + "      AND r.retry_count < ?) "                                 // 且未达重试上限
                        + ") "
                        + "ORDER BY s.last_message_at ASC LIMIT ?",
                (rs, rowNum) -> rs.getString("id"),
                beforeMs, belowThresholdSentinel, maxRetries, limit
        );
    }

    @Override
    public List<String> findIdsWithLastMessageAfter(long afterMs) {
        return jdbc.query(
                "SELECT id FROM chat_session "
                        + "WHERE last_message_at IS NOT NULL AND last_message_at >= ? "
                        + "ORDER BY last_message_at ASC",
                (rs, rowNum) -> rs.getString("id"),
                afterMs
        );
    }

    @Override
    public void saveFeedback(String sessionId, Feedback feedback) {
        int rows = jdbc.update(
                "UPDATE chat_session SET feedback_rating = ?, feedback_comment = ?, feedback_at = ? WHERE id = ?",
                feedback.getRating() == null ? null : feedback.getRating().name(),
                feedback.getComment(),
                feedback.getUpdatedAt() == null ? null : feedback.getUpdatedAt().toString(),
                sessionId
        );
        log.debug("session-feedback-saved sessionId={} rating={} affectedRows={}",
                sessionId, feedback.getRating(), rows);
    }

    /** 整行映射为 ChatSession 聚合根, findById/findAll/findByShareToken 共用。 */
    private ChatSession mapSession(ResultSet rs) throws SQLException {
        ChatSession s = new ChatSession(
                rs.getString("id"),
                AgentType.valueOf(rs.getString("agent_type")),
                rs.getString("working_dir"),
                Instant.parse(rs.getString("created_at")),
                loadMessages(rs.getString("id"))
        );
        s.setResumeId(rs.getString("resume_id"));
        s.setTitle(rs.getString("title"));
        s.setEnv(rs.getString("env"));
        s.setClientIp(rs.getString("client_ip"));
        s.setUserId(rs.getString("user_id"));
        s.setUserName(rs.getString("user_name"));
        s.setFeedback(readFeedback(rs));
        return s;
    }

    /** feedback_at 为空表示从未评价过, 直接返回 null; 否则装配 Feedback 值对象。 */
    private Feedback readFeedback(ResultSet rs) throws SQLException {
        String feedbackAt = rs.getString("feedback_at");
        if (feedbackAt == null) {
            return null;
        }
        String ratingName = rs.getString("feedback_rating");
        FeedbackRating rating = ratingName == null ? null : FeedbackRating.valueOf(ratingName);
        return new Feedback(rating, rs.getString("feedback_comment"), Instant.parse(feedbackAt));
    }

    private List<ChatMessage> loadMessages(String sessionId) {
        return jdbc.query(
                "SELECT id, role, content, timestamp FROM chat_message WHERE session_id = ? ORDER BY id ASC",
                (rs, rowNum) -> new ChatMessage(
                        rs.getLong("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        Instant.parse(rs.getString("timestamp"))
                ),
                sessionId
        );
    }
}
