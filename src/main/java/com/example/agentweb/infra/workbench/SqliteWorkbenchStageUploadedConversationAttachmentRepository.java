package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentQueryService;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic Stage 浏览器上传附件聚合的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-05
 */
@Repository
public class SqliteWorkbenchStageUploadedConversationAttachmentRepository
        implements WorkbenchStageUploadedConversationAttachmentRepository,
        WorkbenchStageUploadedConversationAttachmentQueryService {

    private static final String COLUMNS =
            "a.attachment_id, a.owner_id, a.owner_name, a.workbench_id, "
                    + "a.stage_instance_identifier, a.conversation_id, "
                    + "a.conversation_generation, a.display_name, "
                    + "a.media_type, a.size_bytes, a.sha256, a.storage_key, "
                    + "a.status, a.bound_run_id, a.created_at, a.expires_at, "
                    + "a.updated_at, a.version, "
                    + "w.owner_id AS workbench_owner_id, "
                    + "w.owner_name AS workbench_owner_name, "
                    + "c.session_id AS conversation_session_id, "
                    + "c.created_by_id AS conversation_owner_id, "
                    + "c.created_by_name AS conversation_owner_name";

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchStageUploadedConversationAttachmentRepository(
            JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchStageUploadedConversationAttachment> findById(
            String attachmentId) {
        List<WorkbenchStageUploadedConversationAttachment> attachments =
                jdbc.query(
                        selectByIdSql(), this::read,
                        requireIdentifier(attachmentId));
        return attachments.isEmpty()
                ? Optional.empty() : Optional.of(attachments.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public long countAvailable(
            WorkbenchStageUploadedAttachmentBinding binding, Instant now) {
        if (binding == null || now == null) {
            throw new IllegalArgumentException(
                    "Stage attachment binding and time are required");
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM workbench_stage_uploaded_attachment "
                        + "WHERE owner_id = ? AND workbench_id = ? "
                        + "AND stage_instance_identifier = ? "
                        + "AND conversation_id = ? "
                        + "AND conversation_generation = ? "
                        + "AND status = 'AVAILABLE' AND expires_at > ?",
                Long.class, binding.getOwner().getOwnerId(),
                binding.getWorkbenchId().getValue(),
                binding.getStageInstanceIdentifier(),
                binding.getConversationId(),
                binding.getConversationGeneration(), now.toEpochMilli());
        return count == null ? 0L : count.longValue();
    }

    @Override
    public void add(WorkbenchStageUploadedConversationAttachment attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment must not be null");
        }
        jdbc.update(
                "INSERT INTO workbench_stage_uploaded_attachment ("
                        + "attachment_id, owner_id, owner_name, workbench_id, "
                        + "stage_instance_identifier, conversation_id, "
                        + "conversation_generation, display_name, media_type, "
                        + "size_bytes, sha256, storage_key, status, "
                        + "bound_run_id, created_at, expires_at, updated_at, "
                        + "version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                attachment.getAttachmentId(),
                attachment.getOwner().getOwnerId(),
                attachment.getOwner().getOwnerName(),
                attachment.getWorkbenchId().getValue(),
                attachment.getStageInstanceIdentifier(),
                attachment.getConversationId(),
                attachment.getConversationGeneration(),
                attachment.getDisplayName(), attachment.getMediaType(),
                attachment.getSize(), attachment.getContentHash(),
                attachment.getStorageKey(), attachment.getStatus().name(),
                attachment.getBoundRunId(),
                attachment.getCreatedAt().toEpochMilli(),
                attachment.getExpiresAt().toEpochMilli(),
                attachment.getUpdatedAt().toEpochMilli(),
                attachment.getVersion());
    }

    @Override
    public void update(
            WorkbenchStageUploadedConversationAttachment attachment,
            long expectedVersion) {
        if (attachment == null || expectedVersion < 0L
                || attachment.getVersion() != expectedVersion + 1L) {
            throw new IllegalArgumentException(
                    "Stage attachment optimistic update is invalid");
        }
        int updated = jdbc.update(
                "UPDATE workbench_stage_uploaded_attachment "
                        + "SET status = ?, bound_run_id = ?, expires_at = ?, "
                        + "updated_at = ?, version = ? "
                        + "WHERE attachment_id = ? AND version = ?",
                attachment.getStatus().name(), attachment.getBoundRunId(),
                attachment.getExpiresAt().toEpochMilli(),
                attachment.getUpdatedAt().toEpochMilli(),
                attachment.getVersion(), attachment.getAttachmentId(),
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Stage attachment optimistic update failed");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkbenchStageUploadedConversationAttachment>
            findCleanupCandidates(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException(
                    "Stage attachment cleanup query is invalid");
        }
        return jdbc.query(
                "SELECT " + COLUMNS + joinedTables()
                        + " WHERE a.status = 'RELEASE_PENDING' "
                        + "OR a.expires_at <= ? "
                        + "ORDER BY a.expires_at, a.attachment_id LIMIT ?",
                this::read, now.toEpochMilli(), limit);
    }

    @Override
    public void delete(
            WorkbenchStageUploadedConversationAttachment attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException(
                    "Stage attachment cleanup candidate is required");
        }
        jdbc.update(
                "DELETE FROM workbench_stage_uploaded_attachment "
                        + "WHERE attachment_id = ? AND storage_key = ? "
                        + "AND version = ?",
                attachment.getAttachmentId(), attachment.getStorageKey(),
                attachment.getVersion());
    }

    private WorkbenchStageUploadedConversationAttachment read(
            ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            OwnerReference owner = OwnerReference.of(
                    resultSet.getString("owner_id"),
                    resultSet.getString("owner_name"));
            WorkbenchStageUploadedConversationAttachment attachment =
                    WorkbenchStageUploadedConversationAttachment.restore(
                            resultSet.getString("attachment_id"),
                            new WorkbenchStageUploadedAttachmentBinding(
                                    owner, WorkbenchId.of(resultSet.getString(
                                    "workbench_id")),
                                    resultSet.getString(
                                            "stage_instance_identifier"),
                                    resultSet.getString("conversation_id"),
                                    resultSet.getInt(
                                            "conversation_generation")),
                            resultSet.getString("display_name"),
                            resultSet.getString("media_type"),
                            resultSet.getLong("size_bytes"),
                            resultSet.getString("sha256"),
                            resultSet.getString("storage_key"),
                            UploadedConversationAttachmentStatus.valueOf(
                                    resultSet.getString("status")),
                            resultSet.getString("bound_run_id"),
                            instant(resultSet, "created_at"),
                            instant(resultSet, "expires_at"),
                            instant(resultSet, "updated_at"),
                            resultSet.getLong("version"));
            requireTrustedOwnersAndConversation(resultSet, attachment);
            return attachment;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Corrupt Dynamic Stage uploaded attachment metadata",
                    failure);
        }
    }

    private void requireTrustedOwnersAndConversation(
            ResultSet resultSet,
            WorkbenchStageUploadedConversationAttachment attachment)
            throws SQLException {
        attachment.requireOwnedBy(OwnerReference.of(
                resultSet.getString("workbench_owner_id"),
                resultSet.getString("workbench_owner_name")));
        attachment.requireOwnedBy(OwnerReference.of(
                resultSet.getString("conversation_owner_id"),
                resultSet.getString("conversation_owner_name")));
        if (!attachment.getConversationId().equals(
                resultSet.getString("conversation_session_id"))) {
            throw new IllegalStateException(
                    "Stage attachment Conversation binding is corrupted");
        }
    }

    private String selectByIdSql() {
        return "SELECT " + COLUMNS + joinedTables()
                + " WHERE a.attachment_id = ?";
    }

    private String joinedTables() {
        return " FROM workbench_stage_uploaded_attachment a "
                + "JOIN workbench w ON w.id = a.workbench_id "
                + "JOIN workbench_stage_conversation c "
                + "ON c.workbench_id = a.workbench_id "
                + "AND c.stage_instance_identifier = "
                + "a.stage_instance_identifier "
                + "AND c.generation = a.conversation_generation "
                + "AND c.session_id = a.conversation_id";
    }

    private Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        return Instant.ofEpochMilli(resultSet.getLong(column));
    }

    private String requireIdentifier(String attachmentId) {
        if (attachmentId == null || attachmentId.trim().isEmpty()
                || attachmentId.length() > 128) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment identifier is invalid");
        }
        return attachmentId.trim();
    }
}
