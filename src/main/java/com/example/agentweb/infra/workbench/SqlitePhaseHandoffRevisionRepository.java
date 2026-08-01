package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseHandoffRevision;
import com.example.agentweb.domain.workbench.PhaseHandoffRevisionRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workspace.RepositoryScope;
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
 * Phase Handoff append-only 历史修订的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqlitePhaseHandoffRevisionRepository
        implements PhaseHandoffRevisionRepository {

    private static final String COLUMNS = "workbench_id, phase, summary, decisions_json, "
            + "open_questions_json, pinned_files_json, referenced_runs_json, content_hash, "
            + "updated_by_id, updated_by_name, updated_at, version";

    private final JdbcTemplate jdbc;
    private final WorkbenchScopeJdbcMapper scopeMapper;
    private final WorkbenchJsonCodec codec;

    public SqlitePhaseHandoffRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.scopeMapper = new WorkbenchScopeJdbcMapper(jdbc);
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void append(PhaseHandoffRevision revision) {
        if (revision == null) {
            throw new IllegalArgumentException("phase handoff revision must not be null");
        }
        try {
            jdbc.update("INSERT INTO workbench_phase_handoff_revision (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    revision.getWorkbenchId().getValue(),
                    revision.getSourcePhase().name(), revision.getSummary(),
                    codec.writeDecisions(revision.getDecisions()),
                    codec.writeOpenQuestions(revision.getOpenQuestions()),
                    codec.writeDocuments(revision.getPinnedFiles()),
                    codec.writeRunReferences(revision.getReferencedRuns()),
                    revision.getContentHash(), revision.getUpdatedBy().getOwnerId(),
                    revision.getUpdatedBy().getOwnerName(),
                    revision.getUpdatedAt().toEpochMilli(), revision.getVersion());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase handoff revision could not be appended: "
                            + identity(revision), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhaseHandoffRevision> findExact(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            long version, String contentHash) {
        String exactHash = requireIdentity(
                workbenchId, sourcePhase, version, contentHash);
        List<RevisionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_phase_handoff_revision "
                        + "WHERE workbench_id=? AND phase=? AND version=? "
                        + "AND content_hash=?",
                this::read, workbenchId.getValue(), sourcePhase.name(),
                version, exactHash);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RevisionRow row = rows.get(0);
        try {
            RepositoryScope scope = scopeMapper.load(row.workbenchId);
            return Optional.of(PhaseHandoffRevision.restore(
                    WorkbenchId.of(row.workbenchId),
                    WorkbenchPhase.valueOf(row.phase), row.summary,
                    codec.readDecisions(row.decisionsJson),
                    codec.readOpenQuestions(row.openQuestionsJson),
                    codec.readDocuments(row.pinnedFilesJson),
                    codec.readRunReferences(row.referencedRunsJson), row.contentHash,
                    OwnerReference.of(row.updatedById, row.updatedByName),
                    row.updatedAt, row.version, scope));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "corrupt phase handoff revision " + row.workbenchId + ":"
                            + row.phase + ":" + row.version + ": "
                            + ex.getMessage(), ex);
        }
    }

    private RevisionRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new RevisionRow(
                rs.getString("workbench_id"), rs.getString("phase"),
                rs.getString("summary"), rs.getString("decisions_json"),
                rs.getString("open_questions_json"),
                rs.getString("pinned_files_json"),
                rs.getString("referenced_runs_json"), rs.getString("content_hash"),
                rs.getString("updated_by_id"), rs.getString("updated_by_name"),
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getLong("version"));
    }

    private String requireIdentity(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            long version, String contentHash) {
        if (workbenchId == null || sourcePhase == null) {
            throw new IllegalArgumentException(
                    "handoff revision workbench id and phase must not be null");
        }
        if (version < 0L) {
            throw new IllegalArgumentException(
                    "handoff revision version must not be negative");
        }
        return DomainText.requireSha256(
                contentHash, "handoff revision content hash");
    }

    private String identity(PhaseHandoffRevision revision) {
        return revision.getWorkbenchId().getValue() + ":"
                + revision.getSourcePhase() + ":" + revision.getVersion();
    }

    private static final class RevisionRow {
        private final String workbenchId;
        private final String phase;
        private final String summary;
        private final String decisionsJson;
        private final String openQuestionsJson;
        private final String pinnedFilesJson;
        private final String referencedRunsJson;
        private final String contentHash;
        private final String updatedById;
        private final String updatedByName;
        private final Instant updatedAt;
        private final long version;

        private RevisionRow(
                String workbenchId, String phase, String summary,
                String decisionsJson, String openQuestionsJson,
                String pinnedFilesJson, String referencedRunsJson,
                String contentHash, String updatedById, String updatedByName,
                Instant updatedAt, long version) {
            this.workbenchId = workbenchId;
            this.phase = phase;
            this.summary = summary;
            this.decisionsJson = decisionsJson;
            this.openQuestionsJson = openQuestionsJson;
            this.pinnedFilesJson = pinnedFilesJson;
            this.referencedRunsJson = referencedRunsJson;
            this.contentHash = contentHash;
            this.updatedById = updatedById;
            this.updatedByName = updatedByName;
            this.updatedAt = updatedAt;
            this.version = version;
        }
    }
}
