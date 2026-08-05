package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
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
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 动态 Workbench Stage Run Snapshot 的 SQLite 适配器。
 *
 * @author alex
 * @since 2026-08-05
 */
@Repository
public class SqliteWorkbenchStageRunSnapshotRepository
        implements WorkbenchStageRunSnapshotRepository {

    private static final Pattern STAGE_IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final String COLUMNS = "run_id, workbench_id, "
            + "stage_instance_identifier, stage_definition_identifier, "
            + "stage_definition_revision, stage_snapshot_hash, "
            + "submission_idempotency_key, submission_request_hash, run_mode, "
            + "repository_scope_hash, workspace_snapshot_id, "
            + "workspace_snapshot_topology_hash, workspace_snapshot_state_hash, "
            + "workspace_snapshot_repository_count, capability_bindings_json, "
            + "capability_snapshot_hash, command_binding_json, "
            + "command_binding_hash, context_version, context_hash, "
            + "context_documents_json, prompt_parts_json, prompt_hash, "
            + "attachments_json, runtime_enforcement_json, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final WorkbenchScopeJdbcMapper scopeMapper;
    private final WorkbenchJsonCodec codec;
    private final WorkbenchStageSnapshotJsonMapper stageSnapshotMapper;

    public SqliteWorkbenchStageRunSnapshotRepository(
            JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.scopeMapper = new WorkbenchScopeJdbcMapper(jdbcTemplate);
        this.codec = new WorkbenchJsonCodec();
        this.stageSnapshotMapper = new WorkbenchStageSnapshotJsonMapper(
                new ObjectMapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(WorkbenchStageRunSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run Snapshot is required");
        }
        try {
            WorkspaceSnapshotReference workspace =
                    snapshot.getWorkspaceSnapshotReference();
            ResolvedCapabilityBinding capability =
                    snapshot.getCapabilityBinding();
            ResolvedCommandBinding command = snapshot.getCommandBinding();
            jdbcTemplate.update(
                    "INSERT INTO workbench_stage_run_snapshot (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshot.getRunId(), snapshot.getWorkbenchId().getValue(),
                    snapshot.getStageInstanceIdentifier(),
                    snapshot.getStageDefinitionIdentifier(),
                    snapshot.getStageDefinitionRevision(),
                    snapshot.getStageSnapshotHash(),
                    snapshot.getSubmissionIdempotencyKey(),
                    snapshot.getSubmissionRequestHash(),
                    snapshot.getRunMode().name(),
                    snapshot.getRepositoryScopeHash(), workspace.getSnapshotId(),
                    workspace.getTopologyHash(), workspace.getStateHash(),
                    workspace.getRepositoryCount(),
                    codec.writeCapabilityBinding(capability),
                    capability.getBindingHash(),
                    command == null ? null : codec.writeCommandBinding(command),
                    command == null ? null : command.getExpandedPromptHash(),
                    snapshot.getContextVersion(), snapshot.getContextHash(),
                    codec.writeContextDocumentSnapshots(
                            snapshot.getContextDocumentReferences()),
                    codec.writePromptParts(snapshot.getPromptParts()),
                    snapshot.getPromptHash(),
                    codec.writeVerifiedStageAttachments(
                            snapshot.getVerifiedAttachments(),
                            snapshot.getVerifiedUploadedAttachments()),
                    codec.writeRuntimeEnforcement(
                            snapshot.getRuntimeEnforcement()),
                    snapshot.getCreatedAt().toEpochMilli());
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "Workbench Stage Run Snapshot could not be added: "
                            + snapshot.getRunId(), exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchStageRunSnapshot> findByRunId(String runId) {
        String identifier = DomainText.require(
                runId, "Workbench Stage Run identifier", 128);
        return find("SELECT " + COLUMNS
                        + " FROM workbench_stage_run_snapshot WHERE run_id=?",
                identifier);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchStageRunSnapshot> findReplayCandidate(
            OwnerReference owner, WorkbenchId workbenchId,
            String stageInstanceIdentifier,
            String submissionIdempotencyKey) {
        if (owner == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "Owner and Workbench are required for Stage Run replay");
        }
        String stageIdentifier = requireStageIdentifier(
                stageInstanceIdentifier);
        String key = DomainText.require(
                submissionIdempotencyKey,
                "Workbench Stage Run idempotency key", 128);
        return find("SELECT " + COLUMNS
                        + " FROM workbench_stage_run_snapshot "
                        + "WHERE workbench_id=? AND stage_instance_identifier=? "
                        + "AND submission_idempotency_key=? "
                        + "AND EXISTS (SELECT 1 FROM workbench w "
                        + "WHERE w.id=workbench_stage_run_snapshot.workbench_id "
                        + "AND w.owner_id=?)",
                workbenchId.getValue(), stageIdentifier, key,
                owner.getOwnerId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchStageRunSnapshot>
            findByWorkbenchStageAndIdempotencyKey(
                    WorkbenchId workbenchId,
                    String stageInstanceIdentifier,
                    String submissionIdempotencyKey) {
        if (workbenchId == null) {
            throw new IllegalArgumentException(
                    "Workbench is required for Stage Run lookup");
        }
        String stageIdentifier = requireStageIdentifier(
                stageInstanceIdentifier);
        String key = DomainText.require(
                submissionIdempotencyKey,
                "Workbench Stage Run idempotency key", 128);
        return find("SELECT " + COLUMNS
                        + " FROM workbench_stage_run_snapshot "
                        + "WHERE workbench_id=? AND stage_instance_identifier=? "
                        + "AND submission_idempotency_key=?",
                workbenchId.getValue(), stageIdentifier, key);
    }

    private Optional<WorkbenchStageRunSnapshot> find(
            String sql, Object... arguments) {
        List<SnapshotRow> rows = jdbcTemplate.query(
                sql, this::read, arguments);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SnapshotRow row = rows.get(0);
        try {
            RepositoryScope scope = scopeMapper.load(row.workbenchId);
            if (!scope.getScopeHash().equals(row.repositoryScopeHash)) {
                throw new IllegalArgumentException(
                        "Stage Run Repository Scope Hash is corrupted");
            }
            WorkbenchStageSnapshot stageSnapshot = loadStageSnapshot(row);
            WorkspaceSnapshotReference workspace =
                    new WorkspaceSnapshotReference(
                            row.workspaceSnapshotId,
                            row.workspaceTopologyHash,
                            row.workspaceStateHash,
                            row.workspaceRepositoryCount);
            verifyWorkspaceSnapshot(workspace);
            ResolvedCapabilityBinding capability =
                    codec.readCapabilityBinding(row.capabilityJson);
            if (!capability.getBindingHash().equals(row.capabilityHash)) {
                throw new IllegalArgumentException(
                        "Stage Run Capability Hash is corrupted");
            }
            ResolvedCommandBinding command = readCommandBinding(row);
            List<WorkbenchContextDocumentSnapshot> contextDocuments =
                    codec.readContextDocumentSnapshots(
                            row.contextDocumentsJson);
            List<PromptPartSnapshot> promptParts =
                    codec.readPromptParts(row.promptPartsJson);
            List<VerifiedWorkbenchRunAttachment> attachments =
                    codec.readVerifiedAttachments(row.attachmentsJson);
            List<VerifiedWorkbenchStageUploadedConversationAttachment>
                    uploadedAttachments =
                    codec.readVerifiedStageUploadedAttachments(
                            row.attachmentsJson);
            RuntimeEnforcementSnapshot runtime =
                    codec.readRuntimeEnforcement(
                            row.runtimeEnforcementJson);
            return Optional.of(WorkbenchStageRunSnapshot.create(
                    row.runId, WorkbenchId.of(row.workbenchId),
                    row.stageInstanceIdentifier, stageSnapshot,
                    row.submissionIdempotencyKey,
                    row.submissionRequestHash,
                    RunMode.valueOf(row.runMode), scope, workspace,
                    capability, command, row.contextVersion,
                    row.contextHash, contextDocuments, promptParts,
                    row.promptHash, runtime, attachments,
                    uploadedAttachments, row.createdAt));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Corrupt Workbench Stage Run Snapshot " + row.runId
                            + ": " + exception.getMessage(), exception);
        }
    }

    private WorkbenchStageSnapshot loadStageSnapshot(SnapshotRow row) {
        List<StageSnapshotRow> rows = jdbcTemplate.query(
                "SELECT stage_snapshot_json, stage_snapshot_hash "
                        + "FROM workbench_stage WHERE workbench_id=? "
                        + "AND stage_instance_identifier=?",
                (resultSet, rowNumber) -> new StageSnapshotRow(
                        resultSet.getString("stage_snapshot_json"),
                        resultSet.getString("stage_snapshot_hash")),
                row.workbenchId, row.stageInstanceIdentifier);
        if (rows.size() != 1) {
            throw new IllegalArgumentException(
                    "Referenced Workbench Stage Snapshot is missing");
        }
        StageSnapshotRow persisted = rows.get(0);
        if (!row.stageSnapshotHash.equals(persisted.snapshotHash)) {
            throw new IllegalArgumentException(
                    "Stage Run Snapshot Hash does not match Workbench Stage");
        }
        WorkbenchStageSnapshot snapshot = stageSnapshotMapper.read(
                persisted.snapshotJson, persisted.snapshotHash);
        if (!row.stageDefinitionIdentifier.equals(
                snapshot.getDefinitionIdentifier())
                || row.stageDefinitionRevision
                != snapshot.getDefinitionRevision()) {
            throw new IllegalArgumentException(
                    "Stage Run Definition does not match Workbench Stage");
        }
        return snapshot;
    }

    private ResolvedCommandBinding readCommandBinding(SnapshotRow row) {
        if (row.commandBindingJson == null) {
            if (row.commandBindingHash != null) {
                throw new IllegalArgumentException(
                        "Stage Run Command binding is incomplete");
            }
            return null;
        }
        ResolvedCommandBinding command =
                codec.readCommandBinding(row.commandBindingJson);
        if (!command.getExpandedPromptHash().equals(
                row.commandBindingHash)) {
            throw new IllegalArgumentException(
                    "Stage Run Command binding Hash is corrupted");
        }
        return command;
    }

    private void verifyWorkspaceSnapshot(
            WorkspaceSnapshotReference reference) {
        List<WorkspaceSnapshotRow> rows = jdbcTemplate.query(
                "SELECT topology_hash, state_hash, repository_count "
                        + "FROM workspace_snapshot WHERE snapshot_id=?",
                (resultSet, rowNumber) -> new WorkspaceSnapshotRow(
                        resultSet.getString("topology_hash"),
                        resultSet.getString("state_hash"),
                        resultSet.getInt("repository_count")),
                reference.getSnapshotId());
        if (rows.size() != 1
                || !rows.get(0).matches(reference)) {
            throw new IllegalArgumentException(
                    "Referenced Workspace Snapshot is missing or corrupted");
        }
    }

    private SnapshotRow read(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SnapshotRow(
                resultSet.getString("run_id"),
                resultSet.getString("workbench_id"),
                resultSet.getString("stage_instance_identifier"),
                resultSet.getString("stage_definition_identifier"),
                resultSet.getLong("stage_definition_revision"),
                resultSet.getString("stage_snapshot_hash"),
                resultSet.getString("submission_idempotency_key"),
                resultSet.getString("submission_request_hash"),
                resultSet.getString("run_mode"),
                resultSet.getString("repository_scope_hash"),
                resultSet.getString("workspace_snapshot_id"),
                resultSet.getString("workspace_snapshot_topology_hash"),
                resultSet.getString("workspace_snapshot_state_hash"),
                resultSet.getInt("workspace_snapshot_repository_count"),
                resultSet.getString("capability_bindings_json"),
                resultSet.getString("capability_snapshot_hash"),
                resultSet.getString("command_binding_json"),
                resultSet.getString("command_binding_hash"),
                resultSet.getLong("context_version"),
                resultSet.getString("context_hash"),
                resultSet.getString("context_documents_json"),
                resultSet.getString("prompt_parts_json"),
                resultSet.getString("prompt_hash"),
                resultSet.getString("attachments_json"),
                resultSet.getString("runtime_enforcement_json"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")));
    }

    private static String requireStageIdentifier(String value) {
        String normalized = DomainText.require(
                value, "Stage Instance identifier", 128);
        if (!STAGE_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Stage Instance identifier is invalid");
        }
        return normalized;
    }

    private record SnapshotRow(
            String runId, String workbenchId,
            String stageInstanceIdentifier,
            String stageDefinitionIdentifier,
            long stageDefinitionRevision, String stageSnapshotHash,
            String submissionIdempotencyKey,
            String submissionRequestHash, String runMode,
            String repositoryScopeHash, String workspaceSnapshotId,
            String workspaceTopologyHash, String workspaceStateHash,
            int workspaceRepositoryCount, String capabilityJson,
            String capabilityHash, String commandBindingJson,
            String commandBindingHash, long contextVersion,
            String contextHash, String contextDocumentsJson,
            String promptPartsJson, String promptHash,
            String attachmentsJson, String runtimeEnforcementJson,
            Instant createdAt) {
    }

    private record StageSnapshotRow(
            String snapshotJson, String snapshotHash) {
    }

    private record WorkspaceSnapshotRow(
            String topologyHash, String stateHash,
            int repositoryCount) {

        private boolean matches(WorkspaceSnapshotReference reference) {
            return topologyHash.equals(reference.getTopologyHash())
                    && stateHash.equals(reference.getStateHash())
                    && repositoryCount == reference.getRepositoryCount();
        }
    }
}
