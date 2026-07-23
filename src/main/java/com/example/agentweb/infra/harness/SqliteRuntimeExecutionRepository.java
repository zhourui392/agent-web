package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeCleanupStatus;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionEvent;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.RuntimeExecutionStatus;
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
 * RuntimeExecution 聚合与追加事件的 SQLite 持久化实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Repository
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteRuntimeExecutionRepository implements RuntimeExecutionRepository {

    private static final String COLUMNS = "execution_id, idempotency_key, run_id, stage, "
            + "attempt_number, snapshot_hash, prompt_hash, runtime, status, runtime_version, "
            + "runtime_handle, last_event_sequence, termination_reason, exit_code, "
            + "evidence_reference, cleanup_status, prepared_at, started_at, "
            + "cancel_requested_at, finished_at";

    private final JdbcTemplate jdbc;

    public SqliteRuntimeExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(RuntimeExecution execution) {
        try {
            jdbc.update("INSERT INTO harness_runtime_execution (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    values(execution));
        } catch (DataAccessException ex) {
            throw new IllegalStateException("runtime execution already exists for attempt or key", ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(RuntimeExecution execution) {
        int rows = jdbc.update("UPDATE harness_runtime_execution SET status=?, runtime_version=?, "
                        + "runtime_handle=?, last_event_sequence=?, termination_reason=?, exit_code=?, "
                        + "evidence_reference=?, cleanup_status=?, started_at=?, cancel_requested_at=?, "
                        + "finished_at=? WHERE execution_id=?",
                execution.getStatus().name(), execution.getRuntimeVersion(), execution.getRuntimeHandle(),
                execution.getLastEventSequence(), execution.getTerminationReason(), execution.getExitCode(),
                execution.getEvidenceReference(), execution.getCleanupStatus().name(),
                millis(execution.getStartedAt()), millis(execution.getCancelRequestedAt()),
                millis(execution.getFinishedAt()), execution.getExecutionId());
        if (rows != 1) {
            throw new IllegalStateException("missing runtime execution: " + execution.getExecutionId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeExecution> findById(String executionId) {
        return first(jdbc.query("SELECT " + COLUMNS
                + " FROM harness_runtime_execution WHERE execution_id=?", this::read, executionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeExecution> findByAttempt(String runId, HarnessStage stage, int attemptNumber) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM harness_runtime_execution "
                        + "WHERE run_id=? AND stage=? AND attempt_number=?",
                this::read, runId, stage.name(), attemptNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeExecution> findByIdempotencyKey(String runId, String idempotencyKey) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM harness_runtime_execution "
                        + "WHERE run_id=? AND idempotency_key=?",
                this::read, runId, idempotencyKey));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendEvent(RuntimeExecutionEvent event) {
        jdbc.update("INSERT OR IGNORE INTO harness_runtime_event "
                        + "(execution_id, sequence, event_type, summary, evidence_reference, occurred_at) "
                        + "VALUES (?,?,?,?,?,?)",
                event.getExecutionId(), event.getSequence(), event.getType().name(),
                event.getSummary(), event.getEvidenceReference(), event.getOccurredAt().toEpochMilli());
    }

    private RuntimeExecution read(ResultSet rs, int rowNumber) throws SQLException {
        return RuntimeExecution.restore(rs.getString("execution_id"), rs.getString("idempotency_key"),
                rs.getString("run_id"), HarnessStage.valueOf(rs.getString("stage")),
                rs.getInt("attempt_number"), rs.getString("snapshot_hash"),
                rs.getString("prompt_hash"), AgentRuntime.valueOf(rs.getString("runtime")),
                RuntimeExecutionStatus.valueOf(rs.getString("status")),
                rs.getString("runtime_version"), rs.getString("runtime_handle"),
                rs.getLong("last_event_sequence"), rs.getString("termination_reason"),
                integer(rs, "exit_code"), rs.getString("evidence_reference"),
                RuntimeCleanupStatus.valueOf(rs.getString("cleanup_status")),
                instant(rs, "prepared_at"), instant(rs, "started_at"),
                instant(rs, "cancel_requested_at"), instant(rs, "finished_at"));
    }

    private Object[] values(RuntimeExecution execution) {
        return new Object[]{execution.getExecutionId(), execution.getIdempotencyKey(),
                execution.getRunId(), execution.getStage().name(), execution.getAttemptNumber(),
                execution.getSnapshotHash(), execution.getPromptHash(), execution.getRuntime().name(),
                execution.getStatus().name(), execution.getRuntimeVersion(), execution.getRuntimeHandle(),
                execution.getLastEventSequence(), execution.getTerminationReason(), execution.getExitCode(),
                execution.getEvidenceReference(), execution.getCleanupStatus().name(),
                millis(execution.getPreparedAt()), millis(execution.getStartedAt()),
                millis(execution.getCancelRequestedAt()), millis(execution.getFinishedAt())};
    }

    private Optional<RuntimeExecution> first(List<RuntimeExecution> executions) {
        return executions.stream().findFirst();
    }

    private Long millis(Instant instant) {
        return instant == null ? null : Long.valueOf(instant.toEpochMilli());
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
