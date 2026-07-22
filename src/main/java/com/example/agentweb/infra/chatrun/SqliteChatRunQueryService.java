package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ActiveChatRunView;
import com.example.agentweb.app.chatrun.ChatRunExecutionContext;
import com.example.agentweb.app.chatrun.ChatRunHistoryMessageView;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.shared.AgentType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * SQLite CQRS projections for run restoration and background execution inputs.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class SqliteChatRunQueryService implements ChatRunQueryService {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUserProvider;

    public SqliteChatRunQueryService(JdbcTemplate jdbc, CurrentUserProvider currentUserProvider) {
        this.jdbc = jdbc;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public List<ActiveChatRunView> findActiveForCurrentUser() {
        boolean filter = currentUserProvider.shouldFilter();
        String sql = "SELECT r.id, r.session_id, r.status, r.last_event_seq, r.started_at, r.created_at, "
                + "s.agent_type, s.working_dir FROM chat_run r JOIN chat_session s ON s.id=r.session_id "
                + "WHERE r.status IN ('PENDING','RUNNING','CANCEL_REQUESTED')"
                + (filter ? " AND (s.user_id IS NULL OR s.user_id=?)" : "")
                + " ORDER BY r.created_at DESC";
        if (filter) {
            return jdbc.query(sql, this::mapActive, currentUserProvider.currentUserId());
        }
        return jdbc.query(sql, this::mapActive);
    }

    @Override
    public long countActiveRuns() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_run "
                + "WHERE status IN ('PENDING','RUNNING','CANCEL_REQUESTED')", Long.class);
        return count == null ? 0L : count.longValue();
    }

    @Override
    public List<String> findActiveRunIds() {
        return jdbc.queryForList("SELECT id FROM chat_run "
                        + "WHERE status IN ('PENDING','RUNNING','CANCEL_REQUESTED') "
                        + "ORDER BY created_at ASC, id ASC",
                String.class);
    }

    @Override
    public Optional<ChatRunExecutionContext> findExecutionContext(String runId) {
        boolean filter = currentUserProvider.shouldFilter();
        String sql = "SELECT r.id AS run_id, r.session_id, r.user_message_id, r.recall_enabled, "
                + "s.agent_type, s.working_dir, s.resume_id, s.env, s.user_id, m.content AS message "
                + "FROM chat_run r JOIN chat_session s ON s.id=r.session_id "
                + "JOIN chat_message m ON m.id=r.user_message_id AND m.session_id=r.session_id "
                + "WHERE r.id=?" + (filter ? " AND (s.user_id IS NULL OR s.user_id=?)" : "");
        List<ChatRunExecutionContext> found = filter
                ? jdbc.query(sql, this::mapExecutionContext, runId, currentUserProvider.currentUserId())
                : jdbc.query(sql, this::mapExecutionContext, runId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private ActiveChatRunView mapActive(ResultSet rs, int rowNum) throws SQLException {
        long startedAt = rs.getLong("started_at");
        Long nullableStartedAt = rs.wasNull() ? null : Long.valueOf(startedAt);
        return new ActiveChatRunView(rs.getString("id"), rs.getString("session_id"),
                ChatRunStatus.valueOf(rs.getString("status")), rs.getString("agent_type"),
                rs.getString("working_dir"), rs.getLong("last_event_seq"), nullableStartedAt,
                rs.getLong("created_at"));
    }

    private ChatRunExecutionContext mapExecutionContext(ResultSet rs, int rowNum) throws SQLException {
        String sessionId = rs.getString("session_id");
        long messageId = rs.getLong("user_message_id");
        return new ChatRunExecutionContext(rs.getString("run_id"), sessionId, messageId,
                AgentType.valueOf(rs.getString("agent_type")), rs.getString("working_dir"),
                rs.getString("resume_id"), rs.getString("env"), rs.getString("user_id"),
                rs.getString("message"), rs.getInt("recall_enabled") != 0,
                loadHistory(sessionId, messageId));
    }

    private List<ChatRunHistoryMessageView> loadHistory(String sessionId, long beforeMessageId) {
        return jdbc.query("SELECT role, content FROM chat_message WHERE session_id=? AND id<? ORDER BY id ASC",
                (rs, rowNum) -> new ChatRunHistoryMessageView(
                        rs.getString("role"), rs.getString("content")),
                sessionId, beforeMessageId);
    }
}
