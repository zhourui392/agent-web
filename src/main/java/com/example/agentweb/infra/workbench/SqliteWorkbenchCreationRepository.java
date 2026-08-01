package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchCreationReceipt;
import com.example.agentweb.domain.workbench.WorkbenchCreationRepository;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
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
 * Workbench 创建幂等收据的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteWorkbenchCreationRepository
        implements WorkbenchCreationRepository {

    private static final String COLUMNS = "owner_id, owner_name, idempotency_key, "
            + "request_hash, workbench_id, created_at";

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchCreationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchCreationReceipt> findByOwnerAndIdempotencyKey(
            OwnerReference owner, String idempotencyKey) {
        requireLookup(owner, idempotencyKey);
        List<ReceiptRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_creation_request "
                        + "WHERE owner_id=? AND idempotency_key=?",
                this::read, owner.getOwnerId(), idempotencyKey);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ReceiptRow row = rows.get(0);
        try {
            return Optional.of(WorkbenchCreationReceipt.restore(
                    OwnerReference.of(row.ownerId, row.ownerName), row.idempotencyKey,
                    row.requestHash, WorkbenchId.of(row.workbenchId), row.createdAt));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "corrupt workbench creation receipt " + row.ownerId + ":"
                            + row.idempotencyKey + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(WorkbenchCreationReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "workbench creation receipt must not be null");
        }
        try {
            jdbc.update("INSERT INTO workbench_creation_request (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?)",
                    receipt.getOwner().getOwnerId(), receipt.getOwner().getOwnerName(),
                    receipt.getIdempotencyKey(), receipt.getRequestHash(),
                    receipt.getWorkbenchId().getValue(),
                    receipt.getCreatedAt().toEpochMilli());
        } catch (DataAccessException ex) {
            handleDuplicateOrRethrow(receipt, ex);
        }
    }

    private void handleDuplicateOrRethrow(
            WorkbenchCreationReceipt candidate, DataAccessException cause) {
        Optional<WorkbenchCreationReceipt> existing =
                findByOwnerAndIdempotencyKey(
                        candidate.getOwner(), candidate.getIdempotencyKey());
        if (!existing.isPresent()) {
            throw new IllegalStateException(
                    "workbench creation receipt could not be added: "
                            + candidate.getOwner().getOwnerId() + ":"
                            + candidate.getIdempotencyKey(), cause);
        }
        WorkbenchId replay = existing.get().requireReplay(
                candidate.getOwner(), candidate.getIdempotencyKey(),
                candidate.getRequestHash());
        if (!replay.equals(candidate.getWorkbenchId())) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "workbench creation idempotency key is already bound to another workbench");
        }
    }

    private ReceiptRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new ReceiptRow(
                rs.getString("owner_id"), rs.getString("owner_name"),
                rs.getString("idempotency_key"), rs.getString("request_hash"),
                rs.getString("workbench_id"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private void requireLookup(OwnerReference owner, String idempotencyKey) {
        if (owner == null || idempotencyKey == null
                || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "creation receipt owner and idempotency key are required");
        }
    }

    private static final class ReceiptRow {
        private final String ownerId;
        private final String ownerName;
        private final String idempotencyKey;
        private final String requestHash;
        private final String workbenchId;
        private final Instant createdAt;

        private ReceiptRow(
                String ownerId, String ownerName, String idempotencyKey,
                String requestHash, String workbenchId, Instant createdAt) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.idempotencyKey = idempotencyKey;
            this.requestHash = requestHash;
            this.workbenchId = workbenchId;
            this.createdAt = createdAt;
        }
    }
}
