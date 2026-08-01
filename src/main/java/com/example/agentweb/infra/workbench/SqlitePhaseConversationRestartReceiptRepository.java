package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceipt;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceiptRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase Conversation restart 幂等收据的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqlitePhaseConversationRestartReceiptRepository
        implements PhaseConversationRestartReceiptRepository {

    private static final String COLUMNS = "owner_id, owner_name, idempotency_key, "
            + "workbench_id, phase, previous_session_id, session_id, "
            + "conversation_generation, workbench_version, created_at";

    private static final String SELECT_COLUMNS =
            "r.owner_id AS receipt_owner_id, r.owner_name AS receipt_owner_name, "
                    + "r.idempotency_key, r.workbench_id, r.phase, "
                    + "r.previous_session_id, r.session_id, "
                    + "r.conversation_generation, r.workbench_version, r.created_at, "
                    + "w.owner_id AS workbench_owner_id, "
                    + "w.owner_name AS workbench_owner_name";

    private final JdbcTemplate jdbc;

    public SqlitePhaseConversationRestartReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhaseConversationRestartReceipt> findByOwnerAndIdempotencyKey(
            OwnerReference owner, String idempotencyKey) {
        if (owner == null) {
            throw new IllegalArgumentException("restart receipt owner is required");
        }
        String key = DomainText.require(
                idempotencyKey, "phase conversation restart idempotency key", 128);
        List<PhaseConversationRestartReceipt> receipts = jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM workbench_phase_conversation_restart_receipt r "
                        + "LEFT JOIN workbench w ON w.id=r.workbench_id "
                        + "WHERE r.owner_id=? AND r.idempotency_key=?",
                this::readReceipt, owner.getOwnerId(), key);
        return receipts.isEmpty() ? Optional.empty() : Optional.of(receipts.get(0));
    }

    @Override
    public void add(PhaseConversationRestartReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("restart receipt must not be null");
        }
        jdbc.update(
                "INSERT INTO workbench_phase_conversation_restart_receipt ("
                        + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                receipt.getOwner().getOwnerId(), receipt.getOwner().getOwnerName(),
                receipt.getIdempotencyKey(), receipt.getWorkbenchId().getValue(),
                receipt.getPhase().name(), receipt.getPreviousSessionId(),
                receipt.getSessionId(), receipt.getConversationGeneration(),
                receipt.getWorkbenchVersion(), receipt.getCreatedAt().toEpochMilli());
    }

    private PhaseConversationRestartReceipt readReceipt(
            ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            PhaseConversationRestartReceipt receipt = PhaseConversationRestartReceipt.restore(
                    OwnerReference.of(
                            resultSet.getString("receipt_owner_id"),
                            resultSet.getString("receipt_owner_name")),
                    resultSet.getString("idempotency_key"),
                    WorkbenchId.of(resultSet.getString("workbench_id")),
                    WorkbenchPhase.valueOf(resultSet.getString("phase")),
                    resultSet.getString("previous_session_id"),
                    resultSet.getString("session_id"),
                    resultSet.getInt("conversation_generation"),
                    resultSet.getLong("workbench_version"),
                    Instant.ofEpochMilli(resultSet.getLong("created_at")));
            receipt.requireWorkbenchOwner(OwnerReference.of(
                    resultSet.getString("workbench_owner_id"),
                    resultSet.getString("workbench_owner_name")));
            return receipt;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Corrupt phase conversation restart receipt", ex);
        }
    }
}
