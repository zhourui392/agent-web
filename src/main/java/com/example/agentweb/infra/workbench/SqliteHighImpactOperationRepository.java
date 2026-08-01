package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationRepository;
import com.example.agentweb.domain.workbench.HighImpactOperationStatus;
import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
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
 * 类型化高影响操作聚合的 SQLite 写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteHighImpactOperationRepository
        implements HighImpactOperationRepository {

    private static final String COLUMNS = "operation_id, workbench_id, source_run_id, "
            + "source_run_safe_summary, phase, operation_type, target_json, "
            + "requested_payload_hash, safe_summary, status, proposed_by_id, "
            + "proposed_by_name, proposed_at, decided_by_id, decided_by_name, "
            + "decision_reason, decided_at, authorization_expires_at, preflight_hash, "
            + "execution_reference, failure_code, updated_at, version";

    private final JdbcTemplate jdbc;
    private final WorkbenchJsonCodec codec;

    public SqliteHighImpactOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(HighImpactOperation operation) {
        requireOperation(operation);
        try {
            jdbc.update("INSERT INTO workbench_high_impact_operation (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    operation.getOperationId(), operation.getWorkbenchId().getValue(),
                    operation.getSourceRun().getRunId(),
                    operation.getSourceRun().getSafeSummary(), operation.getPhase().name(),
                    operation.getType().name(),
                    codec.writeOperationTarget(operation.getTarget()),
                    operation.getRequestedPayloadHash(), operation.getSafeSummary(),
                    operation.getStatus().name(), operation.getProposedBy().getOwnerId(),
                    operation.getProposedBy().getOwnerName(),
                    millis(operation.getProposedAt()), ownerId(operation.getDecidedBy()),
                    ownerName(operation.getDecidedBy()), operation.getDecisionReason(),
                    millis(operation.getDecidedAt()),
                    millis(operation.getAuthorizationExpiresAt()),
                    operation.getPreflightHash(), operation.getExecutionReference(),
                    operation.getFailureCode(), millis(operation.getUpdatedAt()),
                    operation.getVersion());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "high-impact operation could not be added: "
                            + operation.getOperationId(), ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(HighImpactOperation operation) {
        requireOperation(operation);
        long expectedVersion = operation.getVersion() - 1L;
        if (expectedVersion < 0L) {
            throw versionConflict(operation.getOperationId());
        }
        try {
            int rows = jdbc.update(
                    "UPDATE workbench_high_impact_operation SET status=?, "
                            + "decided_by_id=?, decided_by_name=?, decision_reason=?, "
                            + "decided_at=?, authorization_expires_at=?, preflight_hash=?, "
                            + "execution_reference=?, failure_code=?, updated_at=?, version=? "
                            + "WHERE operation_id=? AND version=? "
                            + "AND requested_payload_hash=? AND operation_type=?",
                    operation.getStatus().name(), ownerId(operation.getDecidedBy()),
                    ownerName(operation.getDecidedBy()), operation.getDecisionReason(),
                    millis(operation.getDecidedAt()),
                    millis(operation.getAuthorizationExpiresAt()),
                    operation.getPreflightHash(), operation.getExecutionReference(),
                    operation.getFailureCode(), millis(operation.getUpdatedAt()),
                    operation.getVersion(), operation.getOperationId(), expectedVersion,
                    operation.getRequestedPayloadHash(), operation.getType().name());
            if (rows != 1) {
                throw versionConflict(operation.getOperationId());
            }
        } catch (WorkbenchDomainException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "high-impact operation could not be updated: "
                            + operation.getOperationId(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HighImpactOperation> findById(String operationId) {
        if (operationId == null || operationId.trim().isEmpty()) {
            throw new IllegalArgumentException("operation id must not be blank");
        }
        List<OperationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_high_impact_operation "
                        + "WHERE operation_id=?",
                this::read, operationId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        OperationRow row = rows.get(0);
        try {
            WorkbenchId workbenchId = WorkbenchId.of(row.workbenchId);
            WorkbenchPhase phase = WorkbenchPhase.valueOf(row.phase);
            HighImpactOperationType type =
                    HighImpactOperationType.valueOf(row.operationType);
            HighImpactOperationTarget target =
                    codec.readOperationTarget(row.targetJson, type);
            WorkbenchRunReference sourceRun = WorkbenchRunReference.of(
                    row.sourceRunId, workbenchId, phase, row.sourceRunSafeSummary);
            OwnerReference decidedBy = row.decidedById == null ? null
                    : OwnerReference.of(row.decidedById, row.decidedByName);
            return Optional.of(HighImpactOperation.restore(
                    row.operationId, workbenchId, sourceRun, target,
                    row.requestedPayloadHash, row.safeSummary,
                    HighImpactOperationStatus.valueOf(row.status),
                    OwnerReference.of(row.proposedById, row.proposedByName),
                    row.proposedAt, decidedBy, row.decisionReason, row.decidedAt,
                    row.authorizationExpiresAt, row.preflightHash,
                    row.executionReference, row.failureCode, row.updatedAt,
                    row.version));
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "corrupt high-impact operation " + row.operationId + ": "
                            + ex.getMessage(), ex);
        }
    }

    private OperationRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new OperationRow(
                rs.getString("operation_id"), rs.getString("workbench_id"),
                rs.getString("source_run_id"),
                rs.getString("source_run_safe_summary"), rs.getString("phase"),
                rs.getString("operation_type"), rs.getString("target_json"),
                rs.getString("requested_payload_hash"), rs.getString("safe_summary"),
                rs.getString("status"), rs.getString("proposed_by_id"),
                rs.getString("proposed_by_name"), instant(rs, "proposed_at"),
                rs.getString("decided_by_id"), rs.getString("decided_by_name"),
                rs.getString("decision_reason"), nullableInstant(rs, "decided_at"),
                nullableInstant(rs, "authorization_expires_at"),
                rs.getString("preflight_hash"), rs.getString("execution_reference"),
                rs.getString("failure_code"), instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private void requireOperation(HighImpactOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException(
                    "high-impact operation must not be null");
        }
    }

    private String ownerId(OwnerReference owner) {
        return owner == null ? null : owner.getOwnerId();
    }

    private String ownerName(OwnerReference owner) {
        return owner == null ? null : owner.getOwnerName();
    }

    private Long millis(Instant value) {
        return value == null ? null : Long.valueOf(value.toEpochMilli());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private WorkbenchDomainException versionConflict(String operationId) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.VERSION_CONFLICT,
                "stale or missing high-impact operation: " + operationId);
    }

    private static final class OperationRow {
        private final String operationId;
        private final String workbenchId;
        private final String sourceRunId;
        private final String sourceRunSafeSummary;
        private final String phase;
        private final String operationType;
        private final String targetJson;
        private final String requestedPayloadHash;
        private final String safeSummary;
        private final String status;
        private final String proposedById;
        private final String proposedByName;
        private final Instant proposedAt;
        private final String decidedById;
        private final String decidedByName;
        private final String decisionReason;
        private final Instant decidedAt;
        private final Instant authorizationExpiresAt;
        private final String preflightHash;
        private final String executionReference;
        private final String failureCode;
        private final Instant updatedAt;
        private final long version;

        private OperationRow(
                String operationId, String workbenchId, String sourceRunId,
                String sourceRunSafeSummary, String phase, String operationType,
                String targetJson, String requestedPayloadHash, String safeSummary,
                String status, String proposedById, String proposedByName,
                Instant proposedAt, String decidedById, String decidedByName,
                String decisionReason, Instant decidedAt,
                Instant authorizationExpiresAt, String preflightHash,
                String executionReference, String failureCode,
                Instant updatedAt, long version) {
            this.operationId = operationId;
            this.workbenchId = workbenchId;
            this.sourceRunId = sourceRunId;
            this.sourceRunSafeSummary = sourceRunSafeSummary;
            this.phase = phase;
            this.operationType = operationType;
            this.targetJson = targetJson;
            this.requestedPayloadHash = requestedPayloadHash;
            this.safeSummary = safeSummary;
            this.status = status;
            this.proposedById = proposedById;
            this.proposedByName = proposedByName;
            this.proposedAt = proposedAt;
            this.decidedById = decidedById;
            this.decidedByName = decidedByName;
            this.decisionReason = decisionReason;
            this.decidedAt = decidedAt;
            this.authorizationExpiresAt = authorizationExpiresAt;
            this.preflightHash = preflightHash;
            this.executionReference = executionReference;
            this.failureCode = failureCode;
            this.updatedAt = updatedAt;
            this.version = version;
        }
    }
}
