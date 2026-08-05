package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceipt;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceiptRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 动态 Stage Conversation restart 幂等收据的 SQLite Adapter。
 *
 * @author alex
 * @since 2026-08-05
 */
@Repository
public class SqliteWorkbenchStageConversationRestartReceiptRepository
        implements WorkbenchStageConversationRestartReceiptRepository {

    private static final String COLUMNS =
            "owner_id, owner_name, idempotency_key, workbench_id, "
                    + "stage_instance_identifier, previous_session_id, "
                    + "session_id, conversation_generation, "
                    + "workbench_version, created_at";

    private static final String SELECT_COLUMNS =
            "r.owner_id AS receipt_owner_id, "
                    + "r.owner_name AS receipt_owner_name, "
                    + "r.idempotency_key, r.workbench_id, "
                    + "r.stage_instance_identifier, "
                    + "r.previous_session_id, r.session_id, "
                    + "r.conversation_generation, r.workbench_version, "
                    + "r.created_at, w.owner_id AS workbench_owner_id, "
                    + "w.owner_name AS workbench_owner_name";

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchStageConversationRestartReceiptRepository(
            JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchStageConversationRestartReceipt>
            findByOwnerAndIdempotencyKey(
                    OwnerReference owner, String idempotencyKey) {
        if (owner == null) {
            throw new IllegalArgumentException(
                    "Stage restart receipt Owner is required");
        }
        String key = DomainText.require(
                idempotencyKey,
                "Stage conversation restart idempotency key", 128);
        List<WorkbenchStageConversationRestartReceipt> receipts = jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM workbench_stage_conversation_restart_receipt r "
                        + "LEFT JOIN workbench w ON w.id=r.workbench_id "
                        + "WHERE r.owner_id=? AND r.idempotency_key=?",
                this::readReceipt, owner.getOwnerId(), key);
        return receipts.isEmpty()
                ? Optional.empty() : Optional.of(receipts.get(0));
    }

    @Override
    public void add(WorkbenchStageConversationRestartReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "Stage restart receipt must not be null");
        }
        jdbc.update(
                "INSERT INTO workbench_stage_conversation_restart_receipt ("
                        + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                receipt.getOwner().getOwnerId(),
                receipt.getOwner().getOwnerName(),
                receipt.getIdempotencyKey(),
                receipt.getWorkbenchId().getValue(),
                receipt.getStageInstanceIdentifier(),
                receipt.getPreviousSessionId(), receipt.getSessionId(),
                receipt.getConversationGeneration(),
                receipt.getWorkbenchVersion(),
                receipt.getCreatedAt().toEpochMilli());
    }

    private WorkbenchStageConversationRestartReceipt readReceipt(
            ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            WorkbenchStageConversationRestartReceipt receipt =
                    WorkbenchStageConversationRestartReceipt.restore(
                            OwnerReference.of(
                                    resultSet.getString("receipt_owner_id"),
                                    resultSet.getString("receipt_owner_name")),
                            resultSet.getString("idempotency_key"),
                            WorkbenchId.of(
                                    resultSet.getString("workbench_id")),
                            resultSet.getString(
                                    "stage_instance_identifier"),
                            resultSet.getString("previous_session_id"),
                            resultSet.getString("session_id"),
                            resultSet.getInt("conversation_generation"),
                            resultSet.getLong("workbench_version"),
                            Instant.ofEpochMilli(
                                    resultSet.getLong("created_at")));
            receipt.requireWorkbenchOwner(OwnerReference.of(
                    resultSet.getString("workbench_owner_id"),
                    resultSet.getString("workbench_owner_name")));
            return receipt;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Corrupt Stage conversation restart receipt", exception);
        }
    }
}
