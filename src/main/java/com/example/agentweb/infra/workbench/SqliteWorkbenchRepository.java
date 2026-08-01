package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.ActiveRunReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseConversationReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseState;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Workbench 完整聚合的 SQLite 写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteWorkbenchRepository implements WorkbenchRepository {

    private static final String WORKBENCH_COLUMNS = "id, owner_id, owner_name, title, "
            + "original_goal, agent_type, environment, workspace_root, "
            + "primary_repository_key, repository_scope_hash, creation_snapshot_id, "
            + "creation_snapshot_topology_hash, creation_snapshot_state_hash, "
            + "creation_snapshot_repository_count, active_write_run_id, status, "
            + "created_at, updated_at, version";
    private static final String PHASE_COLUMNS = "workbench_id, phase, phase_order, status, "
            + "conversation_generation, active_run_id, active_run_mode, "
            + "active_run_prepared_at, review_confirmation_id, review_opinion_version, "
            + "review_opinion_hash, last_activity_at, completed_at";
    private static final String CONVERSATION_COLUMNS = "workbench_id, phase, generation, "
            + "session_id, created_by_id, created_by_name, created_at, retired_at";

    private final JdbcTemplate jdbc;
    private final WorkbenchScopeJdbcMapper scopeMapper;

    public SqliteWorkbenchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.scopeMapper = new WorkbenchScopeJdbcMapper(jdbc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Workbench workbench) {
        requireWorkbench(workbench);
        try {
            WorkspaceSnapshotReference snapshot = workbench.getCreationSnapshotReference();
            jdbc.update("INSERT INTO workbench (" + WORKBENCH_COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    workbench.getId().getValue(), workbench.getOwner().getOwnerId(),
                    workbench.getOwner().getOwnerName(), workbench.getTitle(),
                    workbench.getOriginalGoal(), workbench.getAgentType().name(),
                    workbench.getEnvironment(), workbench.getRepositoryScope().getWorkspaceRoot(),
                    workbench.getRepositoryScope().getPrimaryRepositoryKey(),
                    workbench.getRepositoryScope().getScopeHash(), snapshot.getSnapshotId(),
                    snapshot.getTopologyHash(), snapshot.getStateHash(),
                    snapshot.getRepositoryCount(), activeWriteRunId(workbench),
                    workbench.getStatus().name(), millis(workbench.getCreatedAt()),
                    millis(workbench.getUpdatedAt()), workbench.getVersion());
            scopeMapper.insert(workbench.getId().getValue(), workbench.getRepositoryScope());
            insertPhases(workbench);
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "workbench could not be added: " + workbench.getId().getValue(), ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Workbench workbench) {
        requireWorkbench(workbench);
        long expectedVersion = workbench.getVersion() - 1L;
        if (expectedVersion < 0L) {
            throw versionConflict(workbench.getId().getValue());
        }
        try {
            int rows = jdbc.update("UPDATE workbench SET active_write_run_id=?, status=?, "
                            + "updated_at=?, version=? WHERE id=? AND version=?",
                    activeWriteRunId(workbench), workbench.getStatus().name(),
                    millis(workbench.getUpdatedAt()), workbench.getVersion(),
                    workbench.getId().getValue(), expectedVersion);
            if (rows != 1) {
                throw versionConflict(workbench.getId().getValue());
            }
            updatePhases(workbench);
        } catch (WorkbenchDomainException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "workbench could not be updated: " + workbench.getId().getValue(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workbench> findById(WorkbenchId workbenchId) {
        if (workbenchId == null) {
            throw new IllegalArgumentException("workbench id must not be null");
        }
        List<WorkbenchRow> rows = jdbc.query(
                "SELECT " + WORKBENCH_COLUMNS + " FROM workbench WHERE id=?",
                this::readWorkbench, workbenchId.getValue());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        WorkbenchRow row = rows.get(0);
        try {
            RepositoryScope scope = scopeMapper.load(
                    row.id, row.workspaceRoot, row.primaryRepositoryKey,
                    row.repositoryScopeHash);
            List<WorkbenchPhaseState> phases = loadPhases(row.id);
            ActiveRunReference writeLease = findActiveRun(
                    phases, row.activeWriteRunId);
            return Optional.of(Workbench.restore(
                    WorkbenchId.of(row.id),
                    OwnerReference.of(row.ownerId, row.ownerName),
                    row.title, row.originalGoal, AgentType.valueOf(row.agentType),
                    row.environment, scope,
                    new WorkspaceSnapshotReference(
                            row.creationSnapshotId, row.creationSnapshotTopologyHash,
                            row.creationSnapshotStateHash,
                            row.creationSnapshotRepositoryCount),
                    phases, writeLease, WorkbenchStatus.valueOf(row.status),
                    row.createdAt, row.updatedAt, row.version));
        } catch (IllegalArgumentException ex) {
            throw corrupt(row.id, ex.getMessage(), ex);
        }
    }

    private void insertPhases(Workbench workbench) {
        for (WorkbenchPhaseState phase : workbench.getPhases()) {
            insertPhase(workbench.getId().getValue(), phase);
            upsertConversations(workbench.getId().getValue(), phase);
        }
    }

    private void insertPhase(String workbenchId, WorkbenchPhaseState phase) {
        ActiveRunReference activeRun = phase.getActiveRunReference();
        jdbc.update("INSERT INTO workbench_phase (" + PHASE_COLUMNS
                        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                workbenchId, phase.getPhase().name(), phase.getPhase().ordinal(),
                phase.getStatus().name(), phase.getConversationGeneration(),
                activeRun == null ? null : activeRun.getRunId(),
                activeRun == null ? null : activeRun.getRunMode().name(),
                activeRun == null ? null : millis(activeRun.getPreparedAt()),
                activeRun == null ? null : activeRun.getReviewConfirmationId(),
                activeRun == null ? null : activeRun.getReviewOpinionVersion(),
                activeRun == null ? null : activeRun.getReviewOpinionHash(),
                millis(phase.getLastActivityAt()), millis(phase.getCompletedAt()));
    }

    private void updatePhases(Workbench workbench) {
        for (WorkbenchPhaseState phase : workbench.getPhases()) {
            ActiveRunReference activeRun = phase.getActiveRunReference();
            int rows = jdbc.update("UPDATE workbench_phase SET status=?, "
                            + "conversation_generation=?, active_run_id=?, active_run_mode=?, "
                            + "active_run_prepared_at=?, review_confirmation_id=?, "
                            + "review_opinion_version=?, review_opinion_hash=?, "
                            + "last_activity_at=?, completed_at=? "
                            + "WHERE workbench_id=? AND phase=? AND phase_order=?",
                    phase.getStatus().name(), phase.getConversationGeneration(),
                    activeRun == null ? null : activeRun.getRunId(),
                    activeRun == null ? null : activeRun.getRunMode().name(),
                    activeRun == null ? null : millis(activeRun.getPreparedAt()),
                    activeRun == null ? null : activeRun.getReviewConfirmationId(),
                    activeRun == null ? null : activeRun.getReviewOpinionVersion(),
                    activeRun == null ? null : activeRun.getReviewOpinionHash(),
                    millis(phase.getLastActivityAt()), millis(phase.getCompletedAt()),
                    workbench.getId().getValue(), phase.getPhase().name(),
                    phase.getPhase().ordinal());
            if (rows != 1) {
                throw corrupt(workbench.getId().getValue(),
                        "fixed phase row is missing: " + phase.getPhase(), null);
            }
            upsertConversations(workbench.getId().getValue(), phase);
        }
    }

    private void upsertConversations(String workbenchId, WorkbenchPhaseState phase) {
        for (PhaseConversationReference conversation : phase.getConversationHistory()) {
            int rows = jdbc.update("INSERT INTO workbench_phase_conversation ("
                            + CONVERSATION_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(workbench_id, phase, generation) DO UPDATE SET "
                            + "retired_at=excluded.retired_at WHERE "
                            + "session_id=excluded.session_id "
                            + "AND created_by_id=excluded.created_by_id "
                            + "AND created_by_name=excluded.created_by_name "
                            + "AND created_at=excluded.created_at",
                    workbenchId, phase.getPhase().name(), conversation.getGeneration(),
                    conversation.getConversationId(), conversation.getCreatedBy().getOwnerId(),
                    conversation.getCreatedBy().getOwnerName(),
                    millis(conversation.getCreatedAt()), millis(conversation.getRetiredAt()));
            if (rows != 1) {
                throw corrupt(workbenchId,
                        "conversation generation conflicts with immutable history: "
                                + phase.getPhase() + "/" + conversation.getGeneration(), null);
            }
        }
    }

    private List<WorkbenchPhaseState> loadPhases(String workbenchId) {
        List<PhaseRow> rows = jdbc.query(
                "SELECT " + PHASE_COLUMNS + " FROM workbench_phase "
                        + "WHERE workbench_id=? ORDER BY phase_order",
                this::readPhase, workbenchId);
        if (rows.size() != WorkbenchPhase.values().length) {
            throw corrupt(workbenchId,
                    "workbench must contain exactly four persisted phases", null);
        }
        List<WorkbenchPhaseState> phases = new ArrayList<WorkbenchPhaseState>();
        for (int index = 0; index < rows.size(); index++) {
            PhaseRow row = rows.get(index);
            WorkbenchPhase phase = WorkbenchPhase.valueOf(row.phase);
            if (phase.ordinal() != row.phaseOrder || phase.ordinal() != index) {
                throw corrupt(workbenchId,
                        "phase order does not match the fixed phase contract", null);
            }
            ActiveRunReference activeRun = row.activeRunId == null ? null
                    : ActiveRunReference.restore(
                    row.activeRunId, phase, RunMode.valueOf(row.activeRunMode),
                    row.reviewConfirmationId, row.reviewOpinionVersion,
                    row.reviewOpinionHash, row.activeRunPreparedAt);
            verifyReviewProof(workbenchId, activeRun);
            phases.add(WorkbenchPhaseState.restore(
                    phase, WorkbenchPhaseStatus.valueOf(row.status),
                    loadConversations(workbenchId, phase), row.conversationGeneration,
                    activeRun, row.lastActivityAt, row.completedAt));
        }
        return phases;
    }

    private void verifyReviewProof(String workbenchId,
                                   ActiveRunReference activeRun) {
        if (activeRun == null || activeRun.getReviewConfirmationId() == null) {
            return;
        }
        List<ReviewProofRow> rows = jdbc.query(
                "SELECT c.workbench_id, c.opinion_version, c.opinion_hash, "
                        + "c.confirmed_at, o.content_hash "
                        + "FROM workbench_review_modify_confirmation c "
                        + "JOIN workbench_review_opinion o "
                        + "ON o.workbench_id=c.workbench_id "
                        + "AND o.opinion_version=c.opinion_version "
                        + "WHERE c.confirmation_id=?",
                (rs, rowNumber) -> new ReviewProofRow(
                        rs.getString("workbench_id"),
                        rs.getLong("opinion_version"),
                        rs.getString("opinion_hash"),
                        Instant.ofEpochMilli(rs.getLong("confirmed_at")),
                        rs.getString("content_hash")),
                activeRun.getReviewConfirmationId());
        if (rows.size() != 1) {
            throw corrupt(workbenchId,
                    "active review confirmation is missing", null);
        }
        ReviewProofRow proof = rows.get(0);
        if (!workbenchId.equals(proof.workbenchId)
                || activeRun.getReviewOpinionVersion().longValue()
                != proof.opinionVersion
                || !activeRun.getReviewOpinionHash().equals(proof.opinionHash)
                || !proof.opinionHash.equals(proof.contentHash)
                || proof.confirmedAt.isAfter(activeRun.getPreparedAt())) {
            throw corrupt(workbenchId,
                    "active review proof does not match persisted confirmation", null);
        }
    }

    private List<PhaseConversationReference> loadConversations(
            String workbenchId, WorkbenchPhase phase) {
        return jdbc.query("SELECT " + CONVERSATION_COLUMNS
                        + " FROM workbench_phase_conversation "
                        + "WHERE workbench_id=? AND phase=? ORDER BY generation",
                (rs, rowNumber) -> PhaseConversationReference.restore(
                        rs.getString("session_id"), rs.getInt("generation"),
                        OwnerReference.of(
                                rs.getString("created_by_id"),
                                rs.getString("created_by_name")),
                        instant(rs, "created_at"), nullableInstant(rs, "retired_at")),
                workbenchId, phase.name());
    }

    private ActiveRunReference findActiveRun(
            List<WorkbenchPhaseState> phases, String activeWriteRunId) {
        if (activeWriteRunId == null) {
            return null;
        }
        for (WorkbenchPhaseState phase : phases) {
            ActiveRunReference activeRun = phase.getActiveRunReference();
            if (activeRun != null && activeWriteRunId.equals(activeRun.getRunId())) {
                return activeRun;
            }
        }
        throw new IllegalArgumentException(
                "active write lease does not match any persisted phase active run");
    }

    private WorkbenchRow readWorkbench(ResultSet rs, int rowNumber) throws SQLException {
        return new WorkbenchRow(
                rs.getString("id"), rs.getString("owner_id"),
                rs.getString("owner_name"), rs.getString("title"),
                rs.getString("original_goal"), rs.getString("agent_type"),
                rs.getString("environment"), rs.getString("workspace_root"),
                rs.getString("primary_repository_key"),
                rs.getString("repository_scope_hash"),
                rs.getString("creation_snapshot_id"),
                rs.getString("creation_snapshot_topology_hash"),
                rs.getString("creation_snapshot_state_hash"),
                rs.getInt("creation_snapshot_repository_count"),
                rs.getString("active_write_run_id"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private PhaseRow readPhase(ResultSet rs, int rowNumber) throws SQLException {
        return new PhaseRow(
                rs.getString("phase"), rs.getInt("phase_order"),
                rs.getString("status"), rs.getInt("conversation_generation"),
                rs.getString("active_run_id"), rs.getString("active_run_mode"),
                nullableInstant(rs, "active_run_prepared_at"),
                rs.getString("review_confirmation_id"),
                nullableLong(rs, "review_opinion_version"),
                rs.getString("review_opinion_hash"),
                nullableInstant(rs, "last_activity_at"),
                nullableInstant(rs, "completed_at"));
    }

    private static void requireWorkbench(Workbench workbench) {
        if (workbench == null) {
            throw new IllegalArgumentException("workbench must not be null");
        }
    }

    private String activeWriteRunId(Workbench workbench) {
        ActiveRunReference reference = workbench.getActiveWriteRunReference();
        return reference == null ? null : reference.getRunId();
    }

    private WorkbenchDomainException versionConflict(String workbenchId) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.VERSION_CONFLICT,
                "stale or missing workbench: " + workbenchId);
    }

    private IllegalStateException corrupt(String workbenchId, String detail,
                                          Throwable cause) {
        return new IllegalStateException(
                "corrupt workbench " + workbenchId + ": " + detail, cause);
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

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }

    private static final class WorkbenchRow {
        private final String id;
        private final String ownerId;
        private final String ownerName;
        private final String title;
        private final String originalGoal;
        private final String agentType;
        private final String environment;
        private final String workspaceRoot;
        private final String primaryRepositoryKey;
        private final String repositoryScopeHash;
        private final String creationSnapshotId;
        private final String creationSnapshotTopologyHash;
        private final String creationSnapshotStateHash;
        private final int creationSnapshotRepositoryCount;
        private final String activeWriteRunId;
        private final String status;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final long version;

        private WorkbenchRow(
                String id, String ownerId, String ownerName, String title,
                String originalGoal, String agentType, String environment,
                String workspaceRoot, String primaryRepositoryKey,
                String repositoryScopeHash, String creationSnapshotId,
                String creationSnapshotTopologyHash, String creationSnapshotStateHash,
                int creationSnapshotRepositoryCount, String activeWriteRunId,
                String status, Instant createdAt, Instant updatedAt, long version) {
            this.id = id;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.title = title;
            this.originalGoal = originalGoal;
            this.agentType = agentType;
            this.environment = environment;
            this.workspaceRoot = workspaceRoot;
            this.primaryRepositoryKey = primaryRepositoryKey;
            this.repositoryScopeHash = repositoryScopeHash;
            this.creationSnapshotId = creationSnapshotId;
            this.creationSnapshotTopologyHash = creationSnapshotTopologyHash;
            this.creationSnapshotStateHash = creationSnapshotStateHash;
            this.creationSnapshotRepositoryCount = creationSnapshotRepositoryCount;
            this.activeWriteRunId = activeWriteRunId;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.version = version;
        }
    }

    private static final class PhaseRow {
        private final String phase;
        private final int phaseOrder;
        private final String status;
        private final int conversationGeneration;
        private final String activeRunId;
        private final String activeRunMode;
        private final Instant activeRunPreparedAt;
        private final String reviewConfirmationId;
        private final Long reviewOpinionVersion;
        private final String reviewOpinionHash;
        private final Instant lastActivityAt;
        private final Instant completedAt;

        private PhaseRow(
                String phase, int phaseOrder, String status,
                int conversationGeneration, String activeRunId,
                String activeRunMode, Instant activeRunPreparedAt,
                String reviewConfirmationId, Long reviewOpinionVersion,
                String reviewOpinionHash, Instant lastActivityAt,
                Instant completedAt) {
            this.phase = phase;
            this.phaseOrder = phaseOrder;
            this.status = status;
            this.conversationGeneration = conversationGeneration;
            this.activeRunId = activeRunId;
            this.activeRunMode = activeRunMode;
            this.activeRunPreparedAt = activeRunPreparedAt;
            this.reviewConfirmationId = reviewConfirmationId;
            this.reviewOpinionVersion = reviewOpinionVersion;
            this.reviewOpinionHash = reviewOpinionHash;
            this.lastActivityAt = lastActivityAt;
            this.completedAt = completedAt;
        }
    }

    private static final class ReviewProofRow {
        private final String workbenchId;
        private final long opinionVersion;
        private final String opinionHash;
        private final Instant confirmedAt;
        private final String contentHash;

        private ReviewProofRow(
                String workbenchId, long opinionVersion, String opinionHash,
                Instant confirmedAt, String contentHash) {
            this.workbenchId = workbenchId;
            this.opinionVersion = opinionVersion;
            this.opinionHash = opinionHash;
            this.confirmedAt = confirmedAt;
            this.contentHash = contentHash;
        }
    }
}
