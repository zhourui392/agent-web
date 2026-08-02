package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.HighImpactOperationProposalReceipt;
import com.example.agentweb.domain.workbench.HighImpactOperationProposalRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 高影响操作提案幂等收据的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteHighImpactOperationProposalRepository
        implements HighImpactOperationProposalRepository {

    private final JdbcTemplate jdbc;

    public SqliteHighImpactOperationProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<HighImpactOperationProposalReceipt> find(
            OwnerReference owner, WorkbenchId workbenchId,
            String idempotencyKey) {
        if (owner == null || workbenchId == null || idempotencyKey == null
                || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "operation proposal lookup values are required");
        }
        List<HighImpactOperationProposalReceipt> rows = jdbc.query(
                "SELECT owner_id, owner_name, workbench_id, idempotency_key, "
                        + "request_hash, operation_id, created_at "
                        + "FROM workbench_high_impact_operation_proposal "
                        + "WHERE owner_id=? AND workbench_id=? AND idempotency_key=?",
                (rs, rowNumber) -> HighImpactOperationProposalReceipt.restore(
                        OwnerReference.of(
                                rs.getString("owner_id"), rs.getString("owner_name")),
                        WorkbenchId.of(rs.getString("workbench_id")),
                        rs.getString("idempotency_key"), rs.getString("request_hash"),
                        rs.getString("operation_id"),
                        Instant.ofEpochMilli(rs.getLong("created_at"))),
                owner.getOwnerId(), workbenchId.getValue(), idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void add(HighImpactOperationProposalReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "operation proposal receipt must not be null");
        }
        try {
            jdbc.update(
                    "INSERT INTO workbench_high_impact_operation_proposal "
                            + "(owner_id, owner_name, workbench_id, idempotency_key, "
                            + "request_hash, operation_id, created_at) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    receipt.getOwner().getOwnerId(),
                    receipt.getOwner().getOwnerName(),
                    receipt.getWorkbenchId().getValue(),
                    receipt.getIdempotencyKey(), receipt.getRequestHash(),
                    receipt.getOperationId(), receipt.getCreatedAt().toEpochMilli());
        } catch (DataAccessException failure) {
            handleDuplicateOrRethrow(receipt, failure);
        }
    }

    private void handleDuplicateOrRethrow(
            HighImpactOperationProposalReceipt candidate,
            DataAccessException cause) {
        Optional<HighImpactOperationProposalReceipt> existing = find(
                candidate.getOwner(), candidate.getWorkbenchId(),
                candidate.getIdempotencyKey());
        if (!existing.isPresent()) {
            throw new IllegalStateException(
                    "high-impact operation proposal receipt could not be added", cause);
        }
        String replayedOperationId = existing.get().requireReplay(
                candidate.getOwner(), candidate.getWorkbenchId(),
                candidate.getIdempotencyKey(), candidate.getRequestHash());
        if (!replayedOperationId.equals(candidate.getOperationId())) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "operation proposal idempotency key is bound to another operation");
        }
    }
}
