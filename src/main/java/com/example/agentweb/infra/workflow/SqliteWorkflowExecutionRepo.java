package com.example.agentweb.infra.workflow;

import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowExecutionRepository;
import com.example.agentweb.domain.workflow.WorkflowStatus;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * {@link WorkflowExecutionRepository} 的 SQLite 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Repository
public class SqliteWorkflowExecutionRepo implements WorkflowExecutionRepository {

    private static final String EXECUTION_COLUMNS =
            "id, workflow_id, status, inputs_json, started_at, finished_at, error_message, created_by";
    private static final String STEP_COLUMNS =
            "id, execution_id, step_index, step_name, status, prompt, output, "
                    + "error_message, started_at, finished_at";

    private final JdbcTemplate jdbc;

    public SqliteWorkflowExecutionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(WorkflowExecution execution) {
        jdbc.update(
                "INSERT INTO workflow_execution (" + EXECUTION_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?)",
                execution.getId(),
                execution.getWorkflowId(),
                execution.getStatus().name(),
                execution.getInputsJson(),
                toEpochMillis(execution.getStartedAt()),
                toEpochMillis(execution.getFinishedAt()),
                execution.getErrorMessage(),
                execution.getCreatedBy());
    }

    @Override
    public void update(WorkflowExecution execution) {
        jdbc.update(
                "UPDATE workflow_execution SET status=?, finished_at=?, error_message=? WHERE id=?",
                execution.getStatus().name(),
                toEpochMillis(execution.getFinishedAt()),
                execution.getErrorMessage(),
                execution.getId());
    }

    @Override
    public WorkflowExecution findById(String id) {
        List<WorkflowExecution> rows = jdbc.query(
                "SELECT " + EXECUTION_COLUMNS + " FROM workflow_execution WHERE id=?",
                EXECUTION_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<WorkflowExecution> findAll(int offset, int limit) {
        return jdbc.query(
                "SELECT " + EXECUTION_COLUMNS
                        + " FROM workflow_execution ORDER BY started_at DESC LIMIT ? OFFSET ?",
                EXECUTION_MAPPER, limit, offset);
    }

    @Override
    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_execution", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public List<WorkflowExecution> findByWorkflowId(String workflowId, int offset, int limit) {
        return jdbc.query(
                "SELECT " + EXECUTION_COLUMNS
                        + " FROM workflow_execution WHERE workflow_id=? "
                        + "ORDER BY started_at DESC LIMIT ? OFFSET ?",
                EXECUTION_MAPPER, workflowId, limit, offset);
    }

    @Override
    public long countByWorkflowId(String workflowId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_execution WHERE workflow_id=?",
                Long.class, workflowId);
        return count == null ? 0L : count;
    }

    @Override
    public void saveStep(WorkflowStepExecution stepExecution) {
        jdbc.update(
                "INSERT INTO workflow_step_execution (" + STEP_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                stepExecution.getId(),
                stepExecution.getExecutionId(),
                stepExecution.getStepIndex(),
                stepExecution.getStepName(),
                stepExecution.getStatus().name(),
                stepExecution.getPrompt(),
                stepExecution.getOutput(),
                stepExecution.getErrorMessage(),
                toEpochMillis(stepExecution.getStartedAt()),
                toEpochMillis(stepExecution.getFinishedAt()));
    }

    @Override
    public void updateStep(WorkflowStepExecution stepExecution) {
        jdbc.update(
                "UPDATE workflow_step_execution SET status=?, output=?, error_message=?, finished_at=? "
                        + "WHERE id=?",
                stepExecution.getStatus().name(),
                stepExecution.getOutput(),
                stepExecution.getErrorMessage(),
                toEpochMillis(stepExecution.getFinishedAt()),
                stepExecution.getId());
    }

    @Override
    public List<WorkflowStepExecution> findStepsByExecutionId(String executionId) {
        return jdbc.query(
                "SELECT " + STEP_COLUMNS
                        + " FROM workflow_step_execution WHERE execution_id=? ORDER BY step_index ASC",
                STEP_MAPPER, executionId);
    }

    private static Long toEpochMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant fromEpochMillis(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static final RowMapper<WorkflowExecution> EXECUTION_MAPPER = (rs, rowNum) ->
            new WorkflowExecution(
                    rs.getString("id"),
                    rs.getString("workflow_id"),
                    WorkflowStatus.valueOf(rs.getString("status")),
                    rs.getString("inputs_json"),
                    fromEpochMillis(rs, "started_at"),
                    fromEpochMillis(rs, "finished_at"),
                    rs.getString("error_message"),
                    rs.getString("created_by"));

    private static final RowMapper<WorkflowStepExecution> STEP_MAPPER = (rs, rowNum) ->
            new WorkflowStepExecution(
                    rs.getString("id"),
                    rs.getString("execution_id"),
                    rs.getInt("step_index"),
                    rs.getString("step_name"),
                    WorkflowStatus.valueOf(rs.getString("status")),
                    rs.getString("prompt"),
                    rs.getString("output"),
                    rs.getString("error_message"),
                    fromEpochMillis(rs, "started_at"),
                    fromEpochMillis(rs, "finished_at"));
}
