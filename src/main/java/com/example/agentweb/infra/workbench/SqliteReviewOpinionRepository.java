package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.ReviewOpinionRepository;
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
 * 不可变 Review Opinion 版本的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteReviewOpinionRepository implements ReviewOpinionRepository {

    private static final String COLUMNS = "workbench_id, opinion_version, "
            + "opinion_content, content_hash, reviewed_by_id, "
            + "reviewed_by_name, reviewed_at";

    private final JdbcTemplate jdbc;

    public SqliteReviewOpinionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(ReviewOpinion opinion) {
        if (opinion == null) {
            throw new IllegalArgumentException("review opinion must not be null");
        }
        try {
            int affected = jdbc.update(
                    "INSERT INTO workbench_review_opinion (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?) "
                            + "ON CONFLICT(workbench_id, opinion_version) "
                            + "DO NOTHING",
                    opinion.getWorkbenchId().getValue(), opinion.getVersion(),
                    opinion.getContent(), opinion.getContentHash(),
                    opinion.getReviewedBy().getOwnerId(),
                    opinion.getReviewedBy().getOwnerName(),
                    opinion.getReviewedAt().toEpochMilli());
            if (affected != 1) {
                throw new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT,
                        "review opinion version already exists");
            }
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "review opinion could not be added: "
                            + opinion.getWorkbenchId().getValue() + ":"
                            + opinion.getVersion(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewOpinion> find(WorkbenchId workbenchId, long version) {
        if (workbenchId == null || version < 1L) {
            throw new IllegalArgumentException(
                    "review opinion workbench and positive version are required");
        }
        List<OpinionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_review_opinion "
                        + "WHERE workbench_id=? AND opinion_version=?",
                this::read, workbenchId.getValue(), version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(restore(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewOpinion> findLatest(WorkbenchId workbenchId) {
        if (workbenchId == null) {
            throw new IllegalArgumentException(
                    "review opinion workbench is required");
        }
        List<OpinionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_review_opinion "
                        + "WHERE workbench_id=? "
                        + "ORDER BY opinion_version DESC LIMIT 1",
                this::read, workbenchId.getValue());
        return rows.isEmpty()
                ? Optional.<ReviewOpinion>empty()
                : Optional.of(restore(rows.get(0)));
    }

    private ReviewOpinion restore(OpinionRow row) {
        try {
            return ReviewOpinion.restore(
                    WorkbenchId.of(row.workbenchId), row.version,
                    row.content, row.contentHash,
                    OwnerReference.of(row.reviewedById, row.reviewedByName),
                    row.reviewedAt);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "corrupt review opinion " + row.workbenchId + ":"
                            + row.version + ": " + ex.getMessage(), ex);
        }
    }

    private OpinionRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new OpinionRow(
                rs.getString("workbench_id"), rs.getLong("opinion_version"),
                rs.getString("opinion_content"), rs.getString("content_hash"),
                rs.getString("reviewed_by_id"),
                rs.getString("reviewed_by_name"),
                Instant.ofEpochMilli(rs.getLong("reviewed_at")));
    }

    private static final class OpinionRow {
        private final String workbenchId;
        private final long version;
        private final String content;
        private final String contentHash;
        private final String reviewedById;
        private final String reviewedByName;
        private final Instant reviewedAt;

        private OpinionRow(
                String workbenchId, long version, String content,
                String contentHash,
                String reviewedById, String reviewedByName, Instant reviewedAt) {
            this.workbenchId = workbenchId;
            this.version = version;
            this.content = content;
            this.contentHash = contentHash;
            this.reviewedById = reviewedById;
            this.reviewedByName = reviewedByName;
            this.reviewedAt = reviewedAt;
        }
    }
}
