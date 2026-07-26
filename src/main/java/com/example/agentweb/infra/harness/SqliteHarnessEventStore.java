package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessRunEvent;
import com.example.agentweb.app.harness.HarnessRunEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * {@link HarnessRunEventStore} 的 SQLite 实现，查 {@code harness_event} 表用于 SSE replay。
 *
 * <p>与 {@code SqliteHarnessRunRepository.loadEvents()} 不同，此实现返回 {@link HarnessRunEvent}
 * 传输对象（stage 为 String），且只提供读路径——事件由 Repository 的 {@code upsertChildren} 持久化。</p>
 *
 * @author zhourui(V33215020)
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteHarnessEventStore implements HarnessRunEventStore {

    private final JdbcTemplate jdbc;

    public SqliteHarnessEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<HarnessRunEvent> findAfterThrough(String runId, long afterExclusive,
                                                  long throughInclusive, int limit) {
        return jdbc.query("SELECT run_id, sequence, event_type, stage, actor, detail, occurred_at "
                        + "FROM harness_event WHERE run_id=? AND sequence > ? AND sequence <= ? "
                        + "ORDER BY sequence LIMIT ?",
                (rs, rowNumber) -> mapEvent(rs), runId, afterExclusive, throughInclusive, limit);
    }

    @Override
    public long findEarliestSequence(String runId) {
        Long earliest = jdbc.queryForObject(
                "SELECT MIN(sequence) FROM harness_event WHERE run_id=?", Long.class, runId);
        return earliest == null ? 0L : earliest;
    }

    @Override
    public long findLastSequence(String runId) {
        Long last = jdbc.queryForObject(
                "SELECT MAX(sequence) FROM harness_event WHERE run_id=?", Long.class, runId);
        return last == null ? 0L : last;
    }

    private HarnessRunEvent mapEvent(ResultSet rs) throws SQLException {
        return new HarnessRunEvent(
                rs.getString("run_id"),
                rs.getLong("sequence"),
                rs.getString("event_type"),
                rs.getString("stage"),
                rs.getString("actor"),
                rs.getString("detail"),
                Instant.ofEpochMilli(rs.getLong("occurred_at")));
    }
}