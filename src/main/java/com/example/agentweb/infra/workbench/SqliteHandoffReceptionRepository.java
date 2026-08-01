package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
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
 * Handoff Reception 当前版本事实的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteHandoffReceptionRepository implements HandoffReceptionRepository {

    private static final String COLUMNS = "workbench_id, target_phase, source_phase, "
            + "source_version, source_hash, accepted_by_id, accepted_by_name, accepted_at";

    private final JdbcTemplate jdbc;

    public SqliteHandoffReceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(HandoffReception reception) {
        if (reception == null) {
            throw new IllegalArgumentException("handoff reception must not be null");
        }
        try {
            jdbc.update("INSERT INTO workbench_handoff_reception (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(workbench_id, target_phase, source_phase) "
                            + "DO UPDATE SET source_version=excluded.source_version, "
                            + "source_hash=excluded.source_hash, "
                            + "accepted_by_id=excluded.accepted_by_id, "
                            + "accepted_by_name=excluded.accepted_by_name, "
                            + "accepted_at=excluded.accepted_at",
                    reception.getWorkbenchId().getValue(),
                    reception.getTargetPhase().name(), reception.getSourcePhase().name(),
                    reception.getSourceVersion(), reception.getSourceHash(),
                    reception.getAcceptedBy().getOwnerId(),
                    reception.getAcceptedBy().getOwnerName(),
                    reception.getAcceptedAt().toEpochMilli());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "handoff reception could not be saved: "
                            + reception.getWorkbenchId().getValue() + ":"
                            + reception.getTargetPhase(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HandoffReception> find(
            WorkbenchId workbenchId, WorkbenchPhase targetPhase,
            WorkbenchPhase sourcePhase) {
        if (workbenchId == null || targetPhase == null || sourcePhase == null) {
            throw new IllegalArgumentException(
                    "reception workbench and phases must not be null");
        }
        List<ReceptionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_handoff_reception "
                        + "WHERE workbench_id=? AND target_phase=? AND source_phase=?",
                this::read, workbenchId.getValue(), targetPhase.name(), sourcePhase.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ReceptionRow row = rows.get(0);
        try {
            return Optional.of(HandoffReception.accept(
                    WorkbenchId.of(row.workbenchId),
                    WorkbenchPhase.valueOf(row.targetPhase),
                    WorkbenchPhase.valueOf(row.sourcePhase), row.sourceVersion,
                    row.sourceHash,
                    OwnerReference.of(row.acceptedById, row.acceptedByName),
                    row.acceptedAt));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "corrupt handoff reception " + row.workbenchId + ":"
                            + row.targetPhase + ": " + ex.getMessage(), ex);
        }
    }

    private ReceptionRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new ReceptionRow(
                rs.getString("workbench_id"), rs.getString("target_phase"),
                rs.getString("source_phase"), rs.getLong("source_version"),
                rs.getString("source_hash"), rs.getString("accepted_by_id"),
                rs.getString("accepted_by_name"),
                Instant.ofEpochMilli(rs.getLong("accepted_at")));
    }

    private static final class ReceptionRow {
        private final String workbenchId;
        private final String targetPhase;
        private final String sourcePhase;
        private final long sourceVersion;
        private final String sourceHash;
        private final String acceptedById;
        private final String acceptedByName;
        private final Instant acceptedAt;

        private ReceptionRow(
                String workbenchId, String targetPhase, String sourcePhase,
                long sourceVersion, String sourceHash, String acceptedById,
                String acceptedByName, Instant acceptedAt) {
            this.workbenchId = workbenchId;
            this.targetPhase = targetPhase;
            this.sourcePhase = sourcePhase;
            this.sourceVersion = sourceVersion;
            this.sourceHash = sourceHash;
            this.acceptedById = acceptedById;
            this.acceptedByName = acceptedByName;
            this.acceptedAt = acceptedAt;
        }
    }
}
