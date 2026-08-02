package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.HandoffSnapshotReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.VerifiedUploadedConversationAttachment;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
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
 * 不可变 Workbench Run Snapshot 的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteWorkbenchRunSnapshotRepository
        implements WorkbenchRunSnapshotRepository {

    private static final String COLUMNS = "run_id, workbench_id, phase, "
            + "submission_idempotency_key, submission_request_hash, run_mode, "
            + "repository_scope_hash, workspace_snapshot_id, "
            + "workspace_snapshot_topology_hash, workspace_snapshot_state_hash, "
            + "workspace_snapshot_repository_count, profile_id, profile_version, "
            + "override_version, capability_bindings_json, capability_snapshot_hash, "
            + "handoff_source_phase, handoff_source_version, handoff_source_hash, "
            + "prompt_parts_json, prompt_hash, attachments_json, "
            + "runtime_enforcement_json, "
            + "review_confirmation_id, review_opinion_version, review_opinion_hash, "
            + "created_at";

    private final JdbcTemplate jdbc;
    private final WorkbenchScopeJdbcMapper scopeMapper;
    private final WorkbenchJsonCodec codec;

    public SqliteWorkbenchRunSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.scopeMapper = new WorkbenchScopeJdbcMapper(jdbc);
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(WorkbenchRunSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("workbench run snapshot must not be null");
        }
        try {
            WorkspaceSnapshotReference workspace = snapshot.getWorkspaceSnapshotReference();
            ResolvedCapabilityBinding capability = snapshot.getCapabilityBinding();
            HandoffSnapshotReference handoff = snapshot.getHandoffSource();
            jdbc.update("INSERT INTO workbench_run_snapshot (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshot.getRunId(), snapshot.getWorkbenchId().getValue(),
                    snapshot.getPhase().name(),
                    snapshot.getSubmissionIdempotencyKey(),
                    snapshot.getSubmissionRequestHash(), snapshot.getRunMode().name(),
                    snapshot.getRepositoryScopeHash(), workspace.getSnapshotId(),
                    workspace.getTopologyHash(), workspace.getStateHash(),
                    workspace.getRepositoryCount(), capability.getProfileId(),
                    capability.getProfileVersion(), snapshot.getOverrideVersion(),
                    codec.writeCapabilityBinding(capability), capability.getBindingHash(),
                    handoff == null ? null : handoff.getSourcePhase().name(),
                    handoff == null ? null : Long.valueOf(handoff.getSourceVersion()),
                    handoff == null ? null : handoff.getSourceHash(),
                    codec.writePromptParts(snapshot.getPromptParts()),
                    snapshot.getPromptHash(),
                    codec.writeVerifiedAttachments(
                            snapshot.getVerifiedAttachments(),
                            snapshot.getVerifiedUploadedAttachments()),
                    codec.writeRuntimeEnforcement(snapshot.getRuntimeEnforcement()),
                    snapshot.getReviewConfirmationId(),
                    snapshot.getReviewOpinionVersion(), snapshot.getReviewOpinionHash(),
                    snapshot.getCreatedAt().toEpochMilli());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "workbench run snapshot could not be added: "
                            + snapshot.getRunId(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchRunSnapshot> findByRunId(String runId) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("workbench run id must not be blank");
        }
        return find("SELECT " + COLUMNS
                        + " FROM workbench_run_snapshot WHERE run_id=?",
                runId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchRunSnapshot> findReplayCandidate(
            OwnerReference owner, WorkbenchId workbenchId,
            WorkbenchPhase phase, String submissionIdempotencyKey) {
        if (owner == null || workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "owner, workbench id and phase are required for replay lookup");
        }
        String key = DomainText.require(
                submissionIdempotencyKey,
                "workbench run submission idempotency key", 128);
        return find("SELECT " + COLUMNS + " FROM workbench_run_snapshot "
                        + "WHERE workbench_id=? AND phase=? "
                        + "AND submission_idempotency_key=? "
                        + "AND EXISTS (SELECT 1 FROM workbench w "
                        + "WHERE w.id=workbench_run_snapshot.workbench_id "
                        + "AND w.owner_id=?)",
                workbenchId.getValue(), phase.name(), key,
                owner.getOwnerId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchRunSnapshot> findByWorkbenchPhaseAndIdempotencyKey(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey) {
        if (workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "workbench id and phase are required for submission lookup");
        }
        String key = DomainText.require(
                submissionIdempotencyKey,
                "workbench run submission idempotency key", 128);
        return find("SELECT " + COLUMNS + " FROM workbench_run_snapshot "
                        + "WHERE workbench_id=? AND phase=? "
                        + "AND submission_idempotency_key=?",
                workbenchId.getValue(), phase.name(), key);
    }

    private Optional<WorkbenchRunSnapshot> find(String sql, Object... arguments) {
        List<SnapshotRow> rows = jdbc.query(
                sql, this::read, arguments);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SnapshotRow row = rows.get(0);
        try {
            RepositoryScope scope = scopeMapper.load(row.workbenchId);
            if (!scope.getScopeHash().equals(row.repositoryScopeHash)) {
                throw new IllegalArgumentException(
                        "run snapshot repository scope hash does not match workbench scope");
            }
            WorkspaceSnapshotReference workspace = new WorkspaceSnapshotReference(
                    row.workspaceSnapshotId, row.workspaceTopologyHash,
                    row.workspaceStateHash, row.workspaceRepositoryCount);
            verifyWorkspaceSnapshot(workspace);
            ResolvedCapabilityBinding capability =
                    codec.readCapabilityBinding(row.capabilityJson);
            if (!capability.getProfileId().equals(row.profileId)
                    || !capability.getProfileVersion().equals(row.profileVersion)
                    || !capability.getBindingHash().equals(row.capabilityHash)) {
                throw new IllegalArgumentException(
                        "run snapshot capability columns do not match binding facts");
            }
            HandoffSnapshotReference handoff = row.handoffSourcePhase == null
                    ? null : HandoffSnapshotReference.of(
                    WorkbenchPhase.valueOf(row.handoffSourcePhase),
                    row.handoffSourceVersion.longValue(), row.handoffSourceHash);
            List<PromptPartSnapshot> promptParts =
                    codec.readPromptParts(row.promptPartsJson);
            List<VerifiedWorkbenchRunAttachment> attachments =
                    codec.readVerifiedAttachments(row.attachmentsJson);
            List<VerifiedUploadedConversationAttachment> uploadedAttachments =
                    codec.readVerifiedUploadedAttachments(row.attachmentsJson);
            RuntimeEnforcementSnapshot runtime =
                    codec.readRuntimeEnforcement(row.runtimeEnforcementJson);
            verifyReviewConfirmation(row);
            return Optional.of(WorkbenchRunSnapshot.restore(
                    row.runId, WorkbenchId.of(row.workbenchId),
                    WorkbenchPhase.valueOf(row.phase),
                    row.submissionIdempotencyKey, row.submissionRequestHash,
                    RunMode.valueOf(row.runMode),
                    scope, workspace, capability, row.overrideVersion, handoff,
                    promptParts, row.promptHash, runtime,
                    attachments, uploadedAttachments,
                    row.reviewConfirmationId, row.reviewOpinionVersion,
                    row.reviewOpinionHash, row.createdAt));
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "corrupt workbench run snapshot " + row.runId + ": "
                            + ex.getMessage(), ex);
        }
    }

    private void verifyWorkspaceSnapshot(WorkspaceSnapshotReference reference) {
        List<WorkspaceSnapshotRow> rows = jdbc.query(
                "SELECT topology_hash, state_hash, repository_count "
                        + "FROM workspace_snapshot WHERE snapshot_id=?",
                (rs, rowNumber) -> new WorkspaceSnapshotRow(
                        rs.getString("topology_hash"), rs.getString("state_hash"),
                        rs.getInt("repository_count")),
                reference.getSnapshotId());
        if (rows.size() != 1) {
            throw new IllegalArgumentException("referenced workspace snapshot is missing");
        }
        WorkspaceSnapshotRow row = rows.get(0);
        if (!row.topologyHash.equals(reference.getTopologyHash())
                || !row.stateHash.equals(reference.getStateHash())
                || row.repositoryCount != reference.getRepositoryCount()) {
            throw new IllegalArgumentException(
                    "workspace snapshot reference does not match immutable snapshot facts");
        }
    }

    private void verifyReviewConfirmation(SnapshotRow snapshot) {
        if (snapshot.reviewConfirmationId == null) {
            return;
        }
        List<ReviewProofRow> rows = jdbc.query(
                "SELECT c.workbench_id, c.opinion_version, c.opinion_hash, c.confirmed_at, "
                        + "o.content_hash FROM workbench_review_modify_confirmation c "
                        + "JOIN workbench_review_opinion o "
                        + "ON o.workbench_id=c.workbench_id "
                        + "AND o.opinion_version=c.opinion_version "
                        + "WHERE c.confirmation_id=?",
                (rs, rowNumber) -> new ReviewProofRow(
                        rs.getString("workbench_id"), rs.getLong("opinion_version"),
                        rs.getString("opinion_hash"),
                        Instant.ofEpochMilli(rs.getLong("confirmed_at")),
                        rs.getString("content_hash")),
                snapshot.reviewConfirmationId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("review confirmation is missing");
        }
        ReviewProofRow proof = rows.get(0);
        if (!snapshot.workbenchId.equals(proof.workbenchId)
                || snapshot.reviewOpinionVersion.longValue() != proof.opinionVersion
                || !snapshot.reviewOpinionHash.equals(proof.opinionHash)
                || !proof.opinionHash.equals(proof.contentHash)
                || proof.confirmedAt.isAfter(snapshot.createdAt)) {
            throw new IllegalArgumentException(
                    "run snapshot review proof does not match persisted confirmation");
        }
    }

    private SnapshotRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new SnapshotRow(
                rs.getString("run_id"), rs.getString("workbench_id"),
                rs.getString("phase"),
                rs.getString("submission_idempotency_key"),
                rs.getString("submission_request_hash"),
                rs.getString("run_mode"),
                rs.getString("repository_scope_hash"),
                rs.getString("workspace_snapshot_id"),
                rs.getString("workspace_snapshot_topology_hash"),
                rs.getString("workspace_snapshot_state_hash"),
                rs.getInt("workspace_snapshot_repository_count"),
                rs.getString("profile_id"), rs.getString("profile_version"),
                nullableLong(rs, "override_version"),
                rs.getString("capability_bindings_json"),
                rs.getString("capability_snapshot_hash"),
                rs.getString("handoff_source_phase"),
                nullableLong(rs, "handoff_source_version"),
                rs.getString("handoff_source_hash"),
                rs.getString("prompt_parts_json"), rs.getString("prompt_hash"),
                rs.getString("attachments_json"),
                rs.getString("runtime_enforcement_json"),
                rs.getString("review_confirmation_id"),
                nullableLong(rs, "review_opinion_version"),
                rs.getString("review_opinion_hash"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }

    private static final class SnapshotRow {
        private final String runId;
        private final String workbenchId;
        private final String phase;
        private final String submissionIdempotencyKey;
        private final String submissionRequestHash;
        private final String runMode;
        private final String repositoryScopeHash;
        private final String workspaceSnapshotId;
        private final String workspaceTopologyHash;
        private final String workspaceStateHash;
        private final int workspaceRepositoryCount;
        private final String profileId;
        private final String profileVersion;
        private final Long overrideVersion;
        private final String capabilityJson;
        private final String capabilityHash;
        private final String handoffSourcePhase;
        private final Long handoffSourceVersion;
        private final String handoffSourceHash;
        private final String promptPartsJson;
        private final String promptHash;
        private final String attachmentsJson;
        private final String runtimeEnforcementJson;
        private final String reviewConfirmationId;
        private final Long reviewOpinionVersion;
        private final String reviewOpinionHash;
        private final Instant createdAt;

        private SnapshotRow(
                String runId, String workbenchId, String phase,
                String submissionIdempotencyKey, String submissionRequestHash,
                String runMode,
                String repositoryScopeHash, String workspaceSnapshotId,
                String workspaceTopologyHash, String workspaceStateHash,
                int workspaceRepositoryCount, String profileId, String profileVersion,
                Long overrideVersion, String capabilityJson, String capabilityHash,
                String handoffSourcePhase, Long handoffSourceVersion,
                String handoffSourceHash, String promptPartsJson, String promptHash,
                String attachmentsJson, String runtimeEnforcementJson,
                String reviewConfirmationId,
                Long reviewOpinionVersion, String reviewOpinionHash, Instant createdAt) {
            this.runId = runId;
            this.workbenchId = workbenchId;
            this.phase = phase;
            this.submissionIdempotencyKey = submissionIdempotencyKey;
            this.submissionRequestHash = submissionRequestHash;
            this.runMode = runMode;
            this.repositoryScopeHash = repositoryScopeHash;
            this.workspaceSnapshotId = workspaceSnapshotId;
            this.workspaceTopologyHash = workspaceTopologyHash;
            this.workspaceStateHash = workspaceStateHash;
            this.workspaceRepositoryCount = workspaceRepositoryCount;
            this.profileId = profileId;
            this.profileVersion = profileVersion;
            this.overrideVersion = overrideVersion;
            this.capabilityJson = capabilityJson;
            this.capabilityHash = capabilityHash;
            this.handoffSourcePhase = handoffSourcePhase;
            this.handoffSourceVersion = handoffSourceVersion;
            this.handoffSourceHash = handoffSourceHash;
            this.promptPartsJson = promptPartsJson;
            this.promptHash = promptHash;
            this.attachmentsJson = attachmentsJson;
            this.runtimeEnforcementJson = runtimeEnforcementJson;
            this.reviewConfirmationId = reviewConfirmationId;
            this.reviewOpinionVersion = reviewOpinionVersion;
            this.reviewOpinionHash = reviewOpinionHash;
            this.createdAt = createdAt;
        }
    }

    private static final class WorkspaceSnapshotRow {
        private final String topologyHash;
        private final String stateHash;
        private final int repositoryCount;

        private WorkspaceSnapshotRow(
                String topologyHash, String stateHash, int repositoryCount) {
            this.topologyHash = topologyHash;
            this.stateHash = stateHash;
            this.repositoryCount = repositoryCount;
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
