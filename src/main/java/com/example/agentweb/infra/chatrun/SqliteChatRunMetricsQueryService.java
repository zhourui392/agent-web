package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunDiagnosticView;
import com.example.agentweb.app.chatrun.ChatRunEventHub;
import com.example.agentweb.app.chatrun.ChatRunMetricsOverview;
import com.example.agentweb.app.chatrun.ChatRunMetricsQueryService;
import com.example.agentweb.domain.chatrun.ChatRunId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ChatRunMetricsQueryService} 的 SQLite 实现:对 chat_run / chat_run_event 做只读聚合,
 * 并叠加内存 {@link ChatRunEventHub} 的实时订阅 gauge。
 *
 * <p>读侧投影:不经聚合根,SQL 内不含业务判断;活动/终态判定用固定枚举集合。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class SqliteChatRunMetricsQueryService implements ChatRunMetricsQueryService {

    private static final String ACTIVE_STATES = "'PENDING','RUNNING','CANCEL_REQUESTED'";

    private final JdbcTemplate jdbc;
    private final ChatRunEventHub eventHub;

    public SqliteChatRunMetricsQueryService(JdbcTemplate jdbc, ChatRunEventHub eventHub) {
        this.jdbc = jdbc;
        this.eventHub = eventHub;
    }

    @Override
    public ChatRunMetricsOverview overview() {
        ChatRunMetricsOverview overview = new ChatRunMetricsOverview();
        Map<String, Long> activeByStatus = groupCount(
                "SELECT status, COUNT(*) FROM chat_run WHERE status IN (" + ACTIVE_STATES + ") GROUP BY status");
        overview.setActiveByStatus(activeByStatus);
        overview.setActiveTotal(sum(activeByStatus));
        overview.setActiveByAgentType(groupCount(
                "SELECT s.agent_type, COUNT(*) FROM chat_run r JOIN chat_session s ON s.id=r.session_id "
                        + "WHERE r.status IN (" + ACTIVE_STATES + ") GROUP BY s.agent_type"));
        overview.setTerminalByStatus(groupCount(
                "SELECT status, COUNT(*) FROM chat_run "
                        + "WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED') GROUP BY status"));
        overview.setFailureByCode(groupCount(
                "SELECT failure_code, COUNT(*) FROM chat_run WHERE failure_code IS NOT NULL GROUP BY failure_code"));
        overview.setAvgDurationSeconds(nullableLong(
                "SELECT CAST(ROUND(AVG(finished_at - started_at) / 1000.0) AS INTEGER) FROM chat_run "
                        + "WHERE started_at IS NOT NULL AND finished_at IS NOT NULL"));
        overview.setMaxDurationSeconds(nullableLong(
                "SELECT CAST(ROUND(MAX(finished_at - started_at) / 1000.0) AS INTEGER) FROM chat_run "
                        + "WHERE started_at IS NOT NULL AND finished_at IS NOT NULL"));
        overview.setEventRows(scalarLong("SELECT COUNT(*) FROM chat_run_event"));
        overview.setEventPayloadBytes(scalarLong("SELECT COALESCE(SUM(payload_size), 0) FROM chat_run_event"));
        overview.setLiveSubscribers(eventHub.totalSubscriberCount());
        overview.setSlowConsumerClosedTotal(eventHub.slowConsumerClosedTotal());
        return overview;
    }

    @Override
    public List<ChatRunDiagnosticView> recentRuns(int limit) {
        return jdbc.query(diagnosticSql() + " ORDER BY r.created_at DESC LIMIT ?",
                this::mapDiagnostic, limit);
    }

    @Override
    public Optional<ChatRunDiagnosticView> diagnose(String runId) {
        List<ChatRunDiagnosticView> found = jdbc.query(diagnosticSql() + " WHERE r.id=?",
                this::mapDiagnostic, runId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private String diagnosticSql() {
        return "SELECT r.id, r.session_id, r.status, s.agent_type, r.last_event_seq, "
                + "r.assistant_message_id, r.failure_code, r.error_message, r.exit_code, "
                + "r.started_at, r.finished_at, r.created_at, "
                + "(SELECT COUNT(*) FROM chat_run_event e WHERE e.run_id=r.id) AS event_count, "
                + "(SELECT MAX(e.created_at) FROM chat_run_event e WHERE e.run_id=r.id) AS last_event_at "
                + "FROM chat_run r JOIN chat_session s ON s.id=r.session_id";
    }

    private ChatRunDiagnosticView mapDiagnostic(ResultSet rs, int rowNum) throws SQLException {
        String runId = rs.getString("id");
        return new ChatRunDiagnosticView(runId, rs.getString("session_id"), rs.getString("status"),
                rs.getString("agent_type"), rs.getLong("last_event_seq"), rs.getLong("event_count"),
                nullableLong(rs, "last_event_at"), eventHub.subscriberCount(ChatRunId.of(runId)),
                nullableLong(rs, "assistant_message_id"), rs.getString("failure_code"),
                rs.getString("error_message"), nullableInt(rs, "exit_code"),
                nullableLong(rs, "started_at"), nullableLong(rs, "finished_at"),
                rs.getLong("created_at"));
    }

    private long scalarLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private Long nullableLong(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(value);
    }

    private Map<String, Long> groupCount(String sql, Object... args) {
        Map<String, Long> result = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> {
            String key = rs.getString(1);
            result.put(key == null ? "unknown" : key, rs.getLong(2));
        };
        jdbc.query(sql, handler, args);
        return result;
    }

    private long sum(Map<String, Long> counts) {
        long total = 0L;
        for (Long value : counts.values()) {
            total += value;
        }
        return total;
    }
}
