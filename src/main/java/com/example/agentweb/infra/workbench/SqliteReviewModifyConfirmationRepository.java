package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmationRepository;
import com.example.agentweb.domain.workbench.ReviewOpinion;
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
 * Review 修改运行人工确认的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteReviewModifyConfirmationRepository
        implements ReviewModifyConfirmationRepository {

    private static final String SELECT_COLUMNS = "c.confirmation_id, c.workbench_id, "
            + "c.opinion_version, c.opinion_hash, c.confirmed_by_id, "
            + "c.confirmed_by_name, c.confirmed_at, o.opinion_content, "
            + "o.content_hash, o.reviewed_by_id, o.reviewed_by_name, "
            + "o.reviewed_at";

    private final JdbcTemplate jdbc;

    public SqliteReviewModifyConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(ReviewModifyConfirmation confirmation) {
        if (confirmation == null) {
            throw new IllegalArgumentException(
                    "review modify confirmation must not be null");
        }
        try {
            jdbc.update("INSERT INTO workbench_review_modify_confirmation "
                            + "(confirmation_id, workbench_id, opinion_version, opinion_hash, "
                            + "confirmed_by_id, confirmed_by_name, confirmed_at) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    confirmation.getConfirmationId(),
                    confirmation.getOpinion().getWorkbenchId().getValue(),
                    confirmation.getOpinionVersion(), confirmation.getOpinionHash(),
                    confirmation.getConfirmedBy().getOwnerId(),
                    confirmation.getConfirmedBy().getOwnerName(),
                    confirmation.getConfirmedAt().toEpochMilli());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "review modify confirmation could not be added: "
                            + confirmation.getConfirmationId(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewModifyConfirmation> findById(String confirmationId) {
        if (confirmationId == null || confirmationId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "review confirmation id must not be blank");
        }
        List<ConfirmationRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM workbench_review_modify_confirmation c "
                        + "JOIN workbench_review_opinion o "
                        + "ON o.workbench_id=c.workbench_id "
                        + "AND o.opinion_version=c.opinion_version "
                        + "WHERE c.confirmation_id=?",
                this::read, confirmationId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(restore(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewModifyConfirmation> findLatest(
            WorkbenchId workbenchId, long opinionVersion,
            String opinionHash) {
        if (workbenchId == null || opinionVersion < 1L
                || opinionHash == null
                || !opinionHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "review confirmation exact opinion proof is required");
        }
        List<ConfirmationRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM workbench_review_modify_confirmation c "
                        + "JOIN workbench_review_opinion o "
                        + "ON o.workbench_id=c.workbench_id "
                        + "AND o.opinion_version=c.opinion_version "
                        + "WHERE c.workbench_id=? AND c.opinion_version=? "
                        + "AND c.opinion_hash=? "
                        + "ORDER BY c.confirmed_at DESC, c.confirmation_id DESC "
                        + "LIMIT 1",
                this::read, workbenchId.getValue(), opinionVersion,
                opinionHash);
        return rows.isEmpty()
                ? Optional.<ReviewModifyConfirmation>empty()
                : Optional.of(restore(rows.get(0)));
    }

    private ReviewModifyConfirmation restore(ConfirmationRow row) {
        try {
            if (!row.opinionHash.equals(row.contentHash)) {
                throw new IllegalArgumentException(
                        "confirmation opinion hash does not match referenced opinion");
            }
            ReviewOpinion opinion = ReviewOpinion.restore(
                    WorkbenchId.of(row.workbenchId), row.opinionVersion,
                    row.content, row.contentHash,
                    OwnerReference.of(row.reviewedById, row.reviewedByName),
                    row.reviewedAt);
            return ReviewModifyConfirmation.confirm(
                    row.confirmationId, opinion,
                    OwnerReference.of(row.confirmedById, row.confirmedByName),
                    row.confirmedAt);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "corrupt review modify confirmation " + row.confirmationId
                            + ": " + ex.getMessage(), ex);
        }
    }

    private ConfirmationRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new ConfirmationRow(
                rs.getString("confirmation_id"), rs.getString("workbench_id"),
                rs.getLong("opinion_version"), rs.getString("opinion_hash"),
                rs.getString("confirmed_by_id"), rs.getString("confirmed_by_name"),
                Instant.ofEpochMilli(rs.getLong("confirmed_at")),
                rs.getString("opinion_content"), rs.getString("content_hash"),
                rs.getString("reviewed_by_id"),
                rs.getString("reviewed_by_name"),
                Instant.ofEpochMilli(rs.getLong("reviewed_at")));
    }

    private static final class ConfirmationRow {
        private final String confirmationId;
        private final String workbenchId;
        private final long opinionVersion;
        private final String opinionHash;
        private final String confirmedById;
        private final String confirmedByName;
        private final Instant confirmedAt;
        private final String content;
        private final String contentHash;
        private final String reviewedById;
        private final String reviewedByName;
        private final Instant reviewedAt;

        private ConfirmationRow(
                String confirmationId, String workbenchId, long opinionVersion,
                String opinionHash, String confirmedById, String confirmedByName,
                Instant confirmedAt, String content, String contentHash,
                String reviewedById, String reviewedByName,
                Instant reviewedAt) {
            this.confirmationId = confirmationId;
            this.workbenchId = workbenchId;
            this.opinionVersion = opinionVersion;
            this.opinionHash = opinionHash;
            this.confirmedById = confirmedById;
            this.confirmedByName = confirmedByName;
            this.confirmedAt = confirmedAt;
            this.content = content;
            this.contentHash = contentHash;
            this.reviewedById = reviewedById;
            this.reviewedByName = reviewedByName;
            this.reviewedAt = reviewedAt;
        }
    }
}
