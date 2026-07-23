package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.RuntimeExecutionQueryService;
import com.example.agentweb.app.harness.RuntimeExecutionView;
import com.example.agentweb.domain.harness.HarnessStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * RuntimeExecution 与 Snapshot MCP 摘要的 SQLite CQRS 投影。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
@SuppressWarnings("PMD.ServiceOrDaoClassShouldEndWithImplRule")
public class SqliteRuntimeExecutionQueryService implements RuntimeExecutionQueryService {

    private final JdbcTemplate jdbc;

    public SqliteRuntimeExecutionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RuntimeExecutionView> find(String runId, HarnessStage stage, int attemptNumber) {
        List<RuntimeExecutionView> views = jdbc.query("SELECT e.execution_id, e.run_id, e.stage, "
                        + "e.attempt_number, e.runtime, e.runtime_version, e.status, e.snapshot_hash, "
                        + "e.prompt_hash, e.termination_reason, e.exit_code, e.evidence_reference, "
                        + "e.cleanup_status, e.prepared_at, e.started_at, e.cancel_requested_at, "
                        + "e.finished_at, s.schema_version, s.selected_mcp_servers_json "
                        + "FROM harness_runtime_execution e JOIN harness_capability_snapshot s "
                        + "ON s.run_id=e.run_id AND s.stage=e.stage "
                        + "AND s.attempt_number=e.attempt_number "
                        + "WHERE e.run_id=? AND e.stage=? AND e.attempt_number=?",
                this::read, runId, stage.name(), attemptNumber);
        return views.stream().findFirst();
    }

    private RuntimeExecutionView read(ResultSet rs, int rowNumber) throws SQLException {
        return new RuntimeExecutionView(rs.getString("execution_id"), rs.getString("run_id"),
                rs.getString("stage"), rs.getInt("attempt_number"), rs.getString("runtime"),
                rs.getString("runtime_version"), rs.getString("status"),
                rs.getString("snapshot_hash"), rs.getString("prompt_hash"),
                rs.getString("termination_reason"), integer(rs, "exit_code"),
                rs.getString("evidence_reference"), rs.getString("cleanup_status"),
                instant(rs, "prepared_at"), instant(rs, "started_at"),
                instant(rs, "cancel_requested_at"), instant(rs, "finished_at"),
                CapabilitySnapshotJdbcCodec.readSelectedMcpServers(
                        rs.getString("selected_mcp_servers_json"),
                        rs.getString("schema_version")));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(value);
    }
}
