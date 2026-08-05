package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageRunReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Dynamic Stage Workbench 完整聚合的 SQLite 写侧 Repository。
 *
 * @author alex
 * @since 2026-08-05
 */
@Repository
public class SqliteWorkbenchRepository implements WorkbenchRepository {

    private static final String WORKBENCH_COLUMNS =
            "id, owner_id, owner_name, title, original_goal, agent_type, "
                    + "environment, workspace_root, primary_repository_key, "
                    + "repository_scope_hash, creation_snapshot_id, "
                    + "creation_snapshot_topology_hash, "
                    + "creation_snapshot_state_hash, "
                    + "creation_snapshot_repository_count, "
                    + "active_write_run_id, status, created_at, updated_at, version";
    private static final String STAGE_COLUMNS =
            "workbench_id, stage_instance_identifier, definition_identifier, "
                    + "definition_revision, definition_hash, sequence_number, "
                    + "stage_snapshot_json, stage_snapshot_hash, status, "
                    + "conversation_generation, active_run_id, active_run_mode, "
                    + "active_run_prepared_at, last_activity_at, completed_at";
    private static final String STAGE_CONVERSATION_COLUMNS =
            "workbench_id, stage_instance_identifier, generation, session_id, "
                    + "created_by_id, created_by_name, created_at, retired_at";

    private final JdbcTemplate jdbc;
    private final WorkbenchScopeJdbcMapper scopeMapper;
    private final WorkbenchStageSnapshotJsonMapper stageSnapshotJsonMapper;

    public SqliteWorkbenchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.scopeMapper = new WorkbenchScopeJdbcMapper(jdbc);
        this.stageSnapshotJsonMapper =
                new WorkbenchStageSnapshotJsonMapper(new ObjectMapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Workbench workbench) {
        requireWorkbench(workbench);
        try {
            WorkspaceSnapshotReference snapshot =
                    workbench.getCreationSnapshotReference();
            jdbc.update(
                    "INSERT INTO workbench (" + WORKBENCH_COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    workbench.getId().getValue(),
                    workbench.getOwner().getOwnerId(),
                    workbench.getOwner().getOwnerName(), workbench.getTitle(),
                    workbench.getOriginalGoal(),
                    workbench.getAgentType().name(),
                    workbench.getEnvironment(),
                    workbench.getRepositoryScope().getWorkspaceRoot(),
                    workbench.getRepositoryScope().getPrimaryRepositoryKey(),
                    workbench.getRepositoryScope().getScopeHash(),
                    snapshot.getSnapshotId(), snapshot.getTopologyHash(),
                    snapshot.getStateHash(), snapshot.getRepositoryCount(),
                    activeWriteRunIdentifier(workbench),
                    workbench.getStatus().name(),
                    millis(workbench.getCreatedAt()),
                    millis(workbench.getUpdatedAt()), workbench.getVersion());
            scopeMapper.insert(
                    workbench.getId().getValue(),
                    workbench.getRepositoryScope());
            insertStages(workbench);
        } catch (DataAccessException failure) {
            throw new IllegalStateException(
                    "Workbench could not be added: "
                            + workbench.getId().getValue(),
                    failure);
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
            int rows = jdbc.update(
                    "UPDATE workbench SET active_write_run_id=?, status=?, "
                            + "updated_at=?, version=? WHERE id=? AND version=?",
                    activeWriteRunIdentifier(workbench),
                    workbench.getStatus().name(),
                    millis(workbench.getUpdatedAt()), workbench.getVersion(),
                    workbench.getId().getValue(), expectedVersion);
            if (rows != 1) {
                throw versionConflict(workbench.getId().getValue());
            }
            updateStages(workbench);
        } catch (WorkbenchDomainException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw new IllegalStateException(
                    "Workbench could not be updated: "
                            + workbench.getId().getValue(),
                    failure);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workbench> findById(WorkbenchId workbenchId) {
        if (workbenchId == null) {
            throw new IllegalArgumentException(
                    "Workbench identifier must not be null");
        }
        List<WorkbenchRow> rows = jdbc.query(
                "SELECT " + WORKBENCH_COLUMNS
                        + " FROM workbench WHERE id=?",
                this::readWorkbench, workbenchId.getValue());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        WorkbenchRow row = rows.get(0);
        try {
            RepositoryScope scope = scopeMapper.load(
                    row.id, row.workspaceRoot, row.primaryRepositoryKey,
                    row.repositoryScopeHash);
            List<WorkbenchStageState> stages = loadStages(row.id);
            WorkbenchStageRunReference writeLease =
                    findActiveWriteRun(stages, row.activeWriteRunId);
            return Optional.of(Workbench.restore(
                    WorkbenchId.of(row.id),
                    OwnerReference.of(row.ownerId, row.ownerName),
                    row.title, row.originalGoal,
                    AgentType.valueOf(row.agentType), row.environment,
                    scope, new WorkspaceSnapshotReference(
                            row.creationSnapshotId,
                            row.creationSnapshotTopologyHash,
                            row.creationSnapshotStateHash,
                            row.creationSnapshotRepositoryCount),
                    stages, writeLease,
                    WorkbenchStatus.valueOf(row.status),
                    row.createdAt, row.updatedAt, row.version));
        } catch (IllegalArgumentException failure) {
            throw corrupt(row.id, failure.getMessage(), failure);
        }
    }

    private void insertStages(Workbench workbench) {
        for (WorkbenchStageState stage : workbench.getStages()) {
            WorkbenchStageSnapshot snapshot = stage.getSnapshot();
            jdbc.update(
                    "INSERT INTO workbench_stage (" + STAGE_COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    workbench.getId().getValue(),
                    stage.getStageInstanceIdentifier(),
                    snapshot.getDefinitionIdentifier(),
                    snapshot.getDefinitionRevision(),
                    snapshot.getDefinitionHash(), snapshot.getSequenceNumber(),
                    stageSnapshotJsonMapper.write(snapshot),
                    snapshot.getSnapshotHash(), stage.getStatus().name(),
                    stage.getConversationGeneration(),
                    stage.getActiveRunIdentifier(),
                    stage.getActiveRunMode() == null
                            ? null : stage.getActiveRunMode().name(),
                    millis(stage.getActiveRunPreparedAt()),
                    millis(stage.getLastActivityAt()),
                    millis(stage.getCompletedAt()));
            upsertStageConversations(workbench.getId().getValue(), stage);
        }
    }

    private void updateStages(Workbench workbench) {
        for (WorkbenchStageState stage : workbench.getStages()) {
            int rows = jdbc.update(
                    "UPDATE workbench_stage SET status=?, "
                            + "conversation_generation=?, active_run_id=?, "
                            + "active_run_mode=?, active_run_prepared_at=?, "
                            + "last_activity_at=?, completed_at=? "
                            + "WHERE workbench_id=? "
                            + "AND stage_instance_identifier=? "
                            + "AND stage_snapshot_hash=?",
                    stage.getStatus().name(),
                    stage.getConversationGeneration(),
                    stage.getActiveRunIdentifier(),
                    stage.getActiveRunMode() == null
                            ? null : stage.getActiveRunMode().name(),
                    millis(stage.getActiveRunPreparedAt()),
                    millis(stage.getLastActivityAt()),
                    millis(stage.getCompletedAt()),
                    workbench.getId().getValue(),
                    stage.getStageInstanceIdentifier(),
                    stage.getSnapshot().getSnapshotHash());
            if (rows != 1) {
                throw corrupt(
                        workbench.getId().getValue(),
                        "Stage row is missing or its Snapshot changed: "
                                + stage.getStageInstanceIdentifier(),
                        null);
            }
            upsertStageConversations(workbench.getId().getValue(), stage);
        }
    }

    private List<WorkbenchStageState> loadStages(String workbenchId) {
        List<StageRow> rows = jdbc.query(
                "SELECT " + STAGE_COLUMNS + " FROM workbench_stage "
                        + "WHERE workbench_id=? ORDER BY sequence_number",
                this::readStage, workbenchId);
        List<WorkbenchStageState> stages =
                new ArrayList<WorkbenchStageState>(rows.size());
        for (StageRow row : rows) {
            WorkbenchStageSnapshot snapshot;
            try {
                snapshot = stageSnapshotJsonMapper.read(
                        row.stageSnapshotJson, row.stageSnapshotHash);
            } catch (IllegalStateException failure) {
                throw corrupt(
                        workbenchId,
                        "Stage Snapshot is invalid: "
                                + row.stageInstanceIdentifier,
                        failure);
            }
            requireSnapshotColumns(workbenchId, row, snapshot);
            stages.add(WorkbenchStageState.restore(
                    row.stageInstanceIdentifier, snapshot,
                    WorkbenchStageStatus.valueOf(row.status),
                    loadStageConversations(
                            workbenchId, row.stageInstanceIdentifier),
                    row.conversationGeneration, row.activeRunId,
                    row.activeRunMode == null
                            ? null : RunMode.valueOf(row.activeRunMode),
                    row.activeRunPreparedAt, row.lastActivityAt,
                    row.completedAt));
        }
        return stages;
    }

    private void upsertStageConversations(
            String workbenchId, WorkbenchStageState stage) {
        for (WorkbenchStageConversationReference conversation
                : stage.getConversationHistory()) {
            int rows = jdbc.update(
                    "INSERT INTO workbench_stage_conversation ("
                            + STAGE_CONVERSATION_COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(workbench_id, "
                            + "stage_instance_identifier, generation) "
                            + "DO UPDATE SET retired_at=excluded.retired_at "
                            + "WHERE session_id=excluded.session_id "
                            + "AND created_by_id=excluded.created_by_id "
                            + "AND created_by_name=excluded.created_by_name "
                            + "AND created_at=excluded.created_at",
                    workbenchId, stage.getStageInstanceIdentifier(),
                    conversation.getGeneration(),
                    conversation.getConversationId(),
                    conversation.getCreatedBy().getOwnerId(),
                    conversation.getCreatedBy().getOwnerName(),
                    millis(conversation.getCreatedAt()),
                    millis(conversation.getRetiredAt()));
            if (rows != 1) {
                throw corrupt(
                        workbenchId,
                        "Stage conversation generation conflicts with "
                                + "immutable history: "
                                + stage.getStageInstanceIdentifier() + "/"
                                + conversation.getGeneration(),
                        null);
            }
        }
    }

    private List<WorkbenchStageConversationReference> loadStageConversations(
            String workbenchId, String stageInstanceIdentifier) {
        return jdbc.query(
                "SELECT " + STAGE_CONVERSATION_COLUMNS
                        + " FROM workbench_stage_conversation "
                        + "WHERE workbench_id=? "
                        + "AND stage_instance_identifier=? "
                        + "ORDER BY generation",
                (resultSet, rowNumber) ->
                        WorkbenchStageConversationReference.restore(
                                resultSet.getString("session_id"),
                                resultSet.getInt("generation"),
                                OwnerReference.of(
                                        resultSet.getString("created_by_id"),
                                        resultSet.getString("created_by_name")),
                                instant(resultSet, "created_at"),
                                nullableInstant(resultSet, "retired_at")),
                workbenchId, stageInstanceIdentifier);
    }

    private void requireSnapshotColumns(
            String workbenchId, StageRow row,
            WorkbenchStageSnapshot snapshot) {
        if (!row.definitionIdentifier.equals(
                snapshot.getDefinitionIdentifier())
                || row.definitionRevision
                != snapshot.getDefinitionRevision()
                || !row.definitionHash.equals(snapshot.getDefinitionHash())
                || row.sequenceNumber != snapshot.getSequenceNumber()) {
            throw corrupt(
                    workbenchId,
                    "Stage Snapshot columns do not match its JSON: "
                            + row.stageInstanceIdentifier,
                    null);
        }
    }

    private WorkbenchStageRunReference findActiveWriteRun(
            List<WorkbenchStageState> stages,
            String activeWriteRunIdentifier) {
        if (activeWriteRunIdentifier == null) {
            return null;
        }
        for (WorkbenchStageState stage : stages) {
            WorkbenchStageRunReference activeRun =
                    stage.getActiveRunReference();
            if (activeRun != null
                    && activeRun.getRunMode().modifiesWorkspace()
                    && activeWriteRunIdentifier.equals(
                    activeRun.getRunIdentifier())) {
                return activeRun;
            }
        }
        throw new IllegalArgumentException(
                "Active write lease does not match any persisted Stage modify Run");
    }

    private WorkbenchRow readWorkbench(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkbenchRow(
                resultSet.getString("id"),
                resultSet.getString("owner_id"),
                resultSet.getString("owner_name"),
                resultSet.getString("title"),
                resultSet.getString("original_goal"),
                resultSet.getString("agent_type"),
                resultSet.getString("environment"),
                resultSet.getString("workspace_root"),
                resultSet.getString("primary_repository_key"),
                resultSet.getString("repository_scope_hash"),
                resultSet.getString("creation_snapshot_id"),
                resultSet.getString("creation_snapshot_topology_hash"),
                resultSet.getString("creation_snapshot_state_hash"),
                resultSet.getInt("creation_snapshot_repository_count"),
                resultSet.getString("active_write_run_id"),
                resultSet.getString("status"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                resultSet.getLong("version"));
    }

    private StageRow readStage(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new StageRow(
                resultSet.getString("stage_instance_identifier"),
                resultSet.getString("definition_identifier"),
                resultSet.getLong("definition_revision"),
                resultSet.getString("definition_hash"),
                resultSet.getInt("sequence_number"),
                resultSet.getString("stage_snapshot_json"),
                resultSet.getString("stage_snapshot_hash"),
                resultSet.getString("status"),
                resultSet.getInt("conversation_generation"),
                resultSet.getString("active_run_id"),
                resultSet.getString("active_run_mode"),
                nullableInstant(resultSet, "active_run_prepared_at"),
                nullableInstant(resultSet, "last_activity_at"),
                nullableInstant(resultSet, "completed_at"));
    }

    private static void requireWorkbench(Workbench workbench) {
        if (workbench == null) {
            throw new IllegalArgumentException(
                    "Workbench must not be null");
        }
    }

    private String activeWriteRunIdentifier(Workbench workbench) {
        WorkbenchStageRunReference reference =
                workbench.getActiveWriteRunReference();
        return reference == null ? null : reference.getRunIdentifier();
    }

    private WorkbenchDomainException versionConflict(
            String workbenchId) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.VERSION_CONFLICT,
                "Stale or missing Workbench: " + workbenchId);
    }

    private IllegalStateException corrupt(
            String workbenchId, String detail, Throwable cause) {
        return new IllegalStateException(
                "Corrupt Workbench " + workbenchId + ": " + detail,
                cause);
    }

    private Long millis(Instant value) {
        return value == null ? null : Long.valueOf(value.toEpochMilli());
    }

    private Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        return Instant.ofEpochMilli(resultSet.getLong(column));
    }

    private Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
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
                String creationSnapshotTopologyHash,
                String creationSnapshotStateHash,
                int creationSnapshotRepositoryCount,
                String activeWriteRunId, String status,
                Instant createdAt, Instant updatedAt, long version) {
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
            this.creationSnapshotTopologyHash =
                    creationSnapshotTopologyHash;
            this.creationSnapshotStateHash = creationSnapshotStateHash;
            this.creationSnapshotRepositoryCount =
                    creationSnapshotRepositoryCount;
            this.activeWriteRunId = activeWriteRunId;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.version = version;
        }
    }

    private static final class StageRow {
        private final String stageInstanceIdentifier;
        private final String definitionIdentifier;
        private final long definitionRevision;
        private final String definitionHash;
        private final int sequenceNumber;
        private final String stageSnapshotJson;
        private final String stageSnapshotHash;
        private final String status;
        private final int conversationGeneration;
        private final String activeRunId;
        private final String activeRunMode;
        private final Instant activeRunPreparedAt;
        private final Instant lastActivityAt;
        private final Instant completedAt;

        private StageRow(
                String stageInstanceIdentifier,
                String definitionIdentifier, long definitionRevision,
                String definitionHash, int sequenceNumber,
                String stageSnapshotJson, String stageSnapshotHash,
                String status, int conversationGeneration,
                String activeRunId, String activeRunMode,
                Instant activeRunPreparedAt,
                Instant lastActivityAt, Instant completedAt) {
            this.stageInstanceIdentifier = stageInstanceIdentifier;
            this.definitionIdentifier = definitionIdentifier;
            this.definitionRevision = definitionRevision;
            this.definitionHash = definitionHash;
            this.sequenceNumber = sequenceNumber;
            this.stageSnapshotJson = stageSnapshotJson;
            this.stageSnapshotHash = stageSnapshotHash;
            this.status = status;
            this.conversationGeneration = conversationGeneration;
            this.activeRunId = activeRunId;
            this.activeRunMode = activeRunMode;
            this.activeRunPreparedAt = activeRunPreparedAt;
            this.lastActivityAt = lastActivityAt;
            this.completedAt = completedAt;
        }
    }
}
