package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRepository;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
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
 * Phase Handoff 聚合的 SQLite 写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqlitePhaseHandoffRepository implements PhaseHandoffRepository {

    private static final String COLUMNS = "workbench_id, phase, summary, decisions_json, "
            + "open_questions_json, pinned_files_json, referenced_runs_json, content_hash, "
            + "updated_by_id, updated_by_name, updated_at, version";

    private final JdbcTemplate jdbc;
    private final WorkbenchScopeJdbcMapper scopeMapper;
    private final WorkbenchJsonCodec codec;

    public SqlitePhaseHandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.scopeMapper = new WorkbenchScopeJdbcMapper(jdbc);
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(PhaseHandoff handoff) {
        requireHandoff(handoff);
        try {
            int rows = jdbc.update("INSERT INTO workbench_phase_handoff ("
                            + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(workbench_id, phase) DO NOTHING",
                    handoff.getWorkbenchId().getValue(), handoff.getSourcePhase().name(),
                    handoff.getSummary(), codec.writeDecisions(handoff.getDecisions()),
                    codec.writeOpenQuestions(handoff.getOpenQuestions()),
                    codec.writeDocuments(handoff.getPinnedFiles()),
                    codec.writeRunReferences(handoff.getReferencedRuns()),
                    handoff.getContentHash(), handoff.getUpdatedBy().getOwnerId(),
                    handoff.getUpdatedBy().getOwnerName(),
                    handoff.getUpdatedAt().toEpochMilli(), handoff.getVersion());
            if (rows != 1) {
                throw versionConflict(identity(handoff));
            }
        } catch (WorkbenchDomainException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase handoff could not be added: " + identity(handoff), ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(PhaseHandoff handoff) {
        requireHandoff(handoff);
        long expectedVersion = handoff.getVersion() - 1L;
        if (expectedVersion < 0L) {
            throw versionConflict(identity(handoff));
        }
        try {
            int rows = jdbc.update("UPDATE workbench_phase_handoff SET summary=?, "
                            + "decisions_json=?, open_questions_json=?, pinned_files_json=?, "
                            + "referenced_runs_json=?, content_hash=?, updated_by_id=?, "
                            + "updated_by_name=?, updated_at=?, version=? "
                            + "WHERE workbench_id=? AND phase=? AND version=?",
                    handoff.getSummary(), codec.writeDecisions(handoff.getDecisions()),
                    codec.writeOpenQuestions(handoff.getOpenQuestions()),
                    codec.writeDocuments(handoff.getPinnedFiles()),
                    codec.writeRunReferences(handoff.getReferencedRuns()),
                    handoff.getContentHash(), handoff.getUpdatedBy().getOwnerId(),
                    handoff.getUpdatedBy().getOwnerName(),
                    handoff.getUpdatedAt().toEpochMilli(), handoff.getVersion(),
                    handoff.getWorkbenchId().getValue(), handoff.getSourcePhase().name(),
                    expectedVersion);
            if (rows != 1) {
                throw versionConflict(identity(handoff));
            }
        } catch (WorkbenchDomainException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase handoff could not be updated: " + identity(handoff), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhaseHandoff> find(WorkbenchId workbenchId,
                                       WorkbenchPhase phase) {
        requireIdentity(workbenchId, phase);
        List<HandoffRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_phase_handoff "
                        + "WHERE workbench_id=? AND phase=?",
                this::read, workbenchId.getValue(), phase.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        HandoffRow row = rows.get(0);
        try {
            RepositoryScope scope = scopeMapper.load(row.workbenchId);
            return Optional.of(PhaseHandoff.restore(
                    WorkbenchId.of(row.workbenchId),
                    WorkbenchPhase.valueOf(row.phase), row.summary,
                    codec.readDecisions(row.decisionsJson),
                    codec.readOpenQuestions(row.openQuestionsJson),
                    codec.readDocuments(row.pinnedFilesJson),
                    codec.readRunReferences(row.referencedRunsJson), row.contentHash,
                    OwnerReference.of(row.updatedById, row.updatedByName),
                    row.updatedAt, row.version, scope));
        } catch (IllegalArgumentException ex) {
            throw corrupt(row.workbenchId + ":" + row.phase, ex);
        }
    }

    private HandoffRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new HandoffRow(
                rs.getString("workbench_id"), rs.getString("phase"),
                rs.getString("summary"), rs.getString("decisions_json"),
                rs.getString("open_questions_json"),
                rs.getString("pinned_files_json"),
                rs.getString("referenced_runs_json"), rs.getString("content_hash"),
                rs.getString("updated_by_id"), rs.getString("updated_by_name"),
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getLong("version"));
    }

    private void requireHandoff(PhaseHandoff handoff) {
        if (handoff == null) {
            throw new IllegalArgumentException("phase handoff must not be null");
        }
    }

    private void requireIdentity(WorkbenchId workbenchId, WorkbenchPhase phase) {
        if (workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "handoff workbench id and phase must not be null");
        }
    }

    private String identity(PhaseHandoff handoff) {
        return handoff.getWorkbenchId().getValue() + ":" + handoff.getSourcePhase();
    }

    private WorkbenchDomainException versionConflict(String identity) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.VERSION_CONFLICT,
                "stale or missing phase handoff: " + identity);
    }

    private IllegalStateException corrupt(String identity, Throwable cause) {
        return new IllegalStateException(
                "corrupt phase handoff " + identity + ": " + cause.getMessage(), cause);
    }

    private static final class HandoffRow {
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

        private HandoffRow(
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
