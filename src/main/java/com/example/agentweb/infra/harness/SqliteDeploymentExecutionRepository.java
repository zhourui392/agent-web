package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.DeploymentExecution;
import com.example.agentweb.domain.harness.DeploymentExecutionRepository;
import com.example.agentweb.domain.harness.DeploymentExecutionStatus;
import com.example.agentweb.domain.harness.DeploymentTemplateReference;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 部署执行聚合的 SQLite 持久化。
 *
 * @author alex
 * @since 2026-07-23
 */
@Repository
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteDeploymentExecutionRepository implements DeploymentExecutionRepository {

    private static final String COLUMNS = "execution_id, idempotency_key, run_id, "
            + "attempt_number, approved_input_baseline_hash, repository_root, git_branch, "
            + "git_head, git_clean, git_diff_hash, git_captured_at, template_id, "
            + "template_version, template_hash, rollback_configured, status, failure_reason, "
            + "prepared_at, started_at, finished_at";

    private final JdbcTemplate jdbc;

    public SqliteDeploymentExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(DeploymentExecution execution) {
        try {
            jdbc.update("INSERT INTO harness_deployment_execution (" + COLUMNS
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", values(execution));
        } catch (DataAccessException ex) {
            throw new IllegalStateException("deployment execution already exists for key", ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DeploymentExecution execution) {
        int rows = jdbc.update("UPDATE harness_deployment_execution SET status=?, "
                        + "failure_reason=?, started_at=?, finished_at=? WHERE execution_id=?",
                execution.getStatus().name(), execution.getFailureReason(),
                millis(execution.getStartedAt()), millis(execution.getFinishedAt()),
                execution.getExecutionId());
        if (rows != 1) {
            throw new IllegalStateException(
                    "missing deployment execution: " + execution.getExecutionId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeploymentExecution> findById(String executionId) {
        return first(jdbc.query("SELECT " + COLUMNS
                + " FROM harness_deployment_execution WHERE execution_id=?", this::read,
                executionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeploymentExecution> findByIdempotencyKey(String runId,
                                                              String idempotencyKey) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM harness_deployment_execution "
                        + "WHERE run_id=? AND idempotency_key=?", this::read,
                runId, idempotencyKey));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentExecution> findUnfinished() {
        return jdbc.query("SELECT " + COLUMNS + " FROM harness_deployment_execution "
                        + "WHERE status IN ('PREPARED','RUNNING') ORDER BY prepared_at, execution_id",
                this::read);
    }

    private DeploymentExecution read(ResultSet rs, int rowNumber) throws SQLException {
        WorkspaceBaseline baseline = WorkspaceBaseline.capture(rs.getString("repository_root"),
                rs.getString("git_branch"), rs.getString("git_head"),
                rs.getInt("git_clean") != 0, rs.getString("git_diff_hash"),
                instant(rs, "git_captured_at"));
        DeploymentTemplateReference template = new DeploymentTemplateReference(
                rs.getString("template_id"), rs.getString("template_version"),
                rs.getString("template_hash"), rs.getInt("rollback_configured") != 0);
        return DeploymentExecution.restore(rs.getString("execution_id"),
                rs.getString("idempotency_key"), rs.getString("run_id"),
                rs.getInt("attempt_number"), rs.getString("approved_input_baseline_hash"),
                baseline, template, DeploymentExecutionStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"), instant(rs, "prepared_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private Object[] values(DeploymentExecution execution) {
        WorkspaceBaseline baseline = execution.getWorkspaceBaseline();
        DeploymentTemplateReference template = execution.getTemplate();
        return new Object[]{execution.getExecutionId(), execution.getIdempotencyKey(),
                execution.getRunId(), execution.getAttemptNumber(),
                execution.getApprovedInputBaselineHash(), baseline.getRepositoryRoot(),
                baseline.getBranch(), baseline.getHead(), baseline.isClean() ? 1 : 0,
                baseline.getDiffHash(), millis(baseline.getCapturedAt()), template.getTemplateId(),
                template.getVersion(), template.getTemplateHash(),
                template.isRollbackConfigured() ? 1 : 0, execution.getStatus().name(),
                execution.getFailureReason(), millis(execution.getPreparedAt()),
                millis(execution.getStartedAt()), millis(execution.getFinishedAt())};
    }

    private Optional<DeploymentExecution> first(List<DeploymentExecution> values) {
        return values.stream().findFirst();
    }

    private Long millis(Instant value) {
        return value == null ? null : Long.valueOf(value.toEpochMilli());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
