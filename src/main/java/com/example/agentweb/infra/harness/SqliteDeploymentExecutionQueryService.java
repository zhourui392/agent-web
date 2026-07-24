package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.DeploymentExecutionQueryService;
import com.example.agentweb.app.harness.DeploymentExecutionView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 部署执行 SQLite 读模型。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteDeploymentExecutionQueryService implements DeploymentExecutionQueryService {

    private static final String COLUMNS = "execution_id, run_id, attempt_number, status, "
            + "approved_input_baseline_hash, template_id, template_version, template_hash, "
            + "failure_reason, prepared_at, started_at, finished_at";

    private final JdbcTemplate jdbc;

    public SqliteDeploymentExecutionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeploymentExecutionView> find(String runId, String executionId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM harness_deployment_execution WHERE run_id=? AND execution_id=?",
                this::read, runId, executionId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentExecutionView> listByRun(String runId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM harness_deployment_execution "
                        + "WHERE run_id=? ORDER BY prepared_at, execution_id", this::read, runId);
    }

    private DeploymentExecutionView read(ResultSet rs, int rowNumber) throws SQLException {
        return new DeploymentExecutionView(rs.getString("execution_id"), rs.getString("run_id"),
                rs.getInt("attempt_number"), rs.getString("status"),
                rs.getString("approved_input_baseline_hash"), rs.getString("template_id"),
                rs.getString("template_version"), rs.getString("template_hash"),
                rs.getString("failure_reason"), rs.getLong("prepared_at"),
                nullableLong(rs, "started_at"), nullableLong(rs, "finished_at"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }
}
