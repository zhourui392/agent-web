package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentQueryService;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 浏览器上传附件聚合的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteUploadedConversationAttachmentRepository
        implements UploadedConversationAttachmentRepository,
        UploadedConversationAttachmentQueryService {

    private static final String COLUMNS =
            "a.attachment_id, a.owner_id, a.owner_name, a.workbench_id, "
                    + "a.phase, a.conversation_id, a.conversation_generation, "
                    + "a.display_name, a.media_type, a.size_bytes, a.sha256, "
                    + "a.storage_key, a.status, a.bound_run_id, a.created_at, "
                    + "a.expires_at, a.updated_at, a.version, "
                    + "w.owner_id AS workbench_owner_id, "
                    + "w.owner_name AS workbench_owner_name";

    private final JdbcTemplate jdbc;

    public SqliteUploadedConversationAttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UploadedConversationAttachment> findById(
            String attachmentId) {
        List<UploadedConversationAttachment> attachments = jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM workbench_uploaded_attachment a "
                        + "JOIN workbench w ON w.id=a.workbench_id "
                        + "WHERE a.attachment_id=?",
                this::read, requireId(attachmentId));
        return attachments.isEmpty()
                ? Optional.empty() : Optional.of(attachments.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public long countAvailable(
            UploadedAttachmentBinding binding, Instant now) {
        if (binding == null || now == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment binding and time are required");
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_uploaded_attachment "
                        + "WHERE owner_id=? AND workbench_id=? AND phase=? "
                        + "AND conversation_id=? AND conversation_generation=? "
                        + "AND status='AVAILABLE' AND expires_at>?",
                Long.class, binding.getOwner().getOwnerId(),
                binding.getWorkbenchId().getValue(), binding.getPhase().name(),
                binding.getConversationId(), binding.getConversationGeneration(),
                now.toEpochMilli());
        return count == null ? 0L : count.longValue();
    }

    @Override
    public void add(UploadedConversationAttachment attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment must not be null");
        }
        jdbc.update(
                "INSERT INTO workbench_uploaded_attachment (attachment_id, "
                        + "owner_id, owner_name, workbench_id, phase, "
                        + "conversation_id, conversation_generation, display_name, "
                        + "media_type, size_bytes, sha256, storage_key, status, "
                        + "bound_run_id, created_at, expires_at, updated_at, version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                attachment.getAttachmentId(), attachment.getOwner().getOwnerId(),
                attachment.getOwner().getOwnerName(),
                attachment.getWorkbenchId().getValue(),
                attachment.getPhase().name(), attachment.getConversationId(),
                attachment.getConversationGeneration(), attachment.getDisplayName(),
                attachment.getMediaType(), attachment.getSize(),
                attachment.getContentHash(), attachment.getStorageKey(),
                attachment.getStatus().name(), attachment.getBoundRunId(),
                attachment.getCreatedAt().toEpochMilli(),
                attachment.getExpiresAt().toEpochMilli(),
                attachment.getUpdatedAt().toEpochMilli(), attachment.getVersion());
    }

    @Override
    public void update(
            UploadedConversationAttachment attachment, long expectedVersion) {
        if (attachment == null || expectedVersion < 0L
                || attachment.getVersion() != expectedVersion + 1L) {
            throw new IllegalArgumentException(
                    "uploaded attachment optimistic update is invalid");
        }
        int updated = jdbc.update(
                "UPDATE workbench_uploaded_attachment SET status=?, bound_run_id=?, "
                        + "expires_at=?, updated_at=?, version=? "
                        + "WHERE attachment_id=? AND version=?",
                attachment.getStatus().name(), attachment.getBoundRunId(),
                attachment.getExpiresAt().toEpochMilli(),
                attachment.getUpdatedAt().toEpochMilli(),
                attachment.getVersion(), attachment.getAttachmentId(),
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "uploaded attachment optimistic update failed");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UploadedConversationAttachment> findCleanupCandidates(
            Instant now, int limit) {
        if (now == null || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException(
                    "uploaded attachment cleanup query is invalid");
        }
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM workbench_uploaded_attachment a "
                        + "JOIN workbench w ON w.id=a.workbench_id "
                        + "WHERE a.status='RELEASE_PENDING' OR a.expires_at<=? "
                        + "ORDER BY a.expires_at, a.attachment_id LIMIT ?",
                this::read, now.toEpochMilli(), limit);
    }

    @Override
    public void delete(UploadedConversationAttachment attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment cleanup candidate is required");
        }
        jdbc.update(
                "DELETE FROM workbench_uploaded_attachment "
                        + "WHERE attachment_id=? AND storage_key=? AND version=?",
                attachment.getAttachmentId(), attachment.getStorageKey(),
                attachment.getVersion());
    }

    private UploadedConversationAttachment read(
            ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            OwnerReference owner = OwnerReference.of(
                    resultSet.getString("owner_id"),
                    resultSet.getString("owner_name"));
            UploadedConversationAttachment attachment =
                    UploadedConversationAttachment.restore(
                            resultSet.getString("attachment_id"),
                            new UploadedAttachmentBinding(
                                    owner,
                                    WorkbenchId.of(resultSet.getString("workbench_id")),
                                    WorkbenchPhase.valueOf(resultSet.getString("phase")),
                                    resultSet.getString("conversation_id"),
                                    resultSet.getInt("conversation_generation")),
                            resultSet.getString("display_name"),
                            resultSet.getString("media_type"),
                            resultSet.getLong("size_bytes"),
                            resultSet.getString("sha256"),
                            resultSet.getString("storage_key"),
                            UploadedConversationAttachmentStatus.valueOf(
                                    resultSet.getString("status")),
                            resultSet.getString("bound_run_id"),
                            Instant.ofEpochMilli(resultSet.getLong("created_at")),
                            Instant.ofEpochMilli(resultSet.getLong("expires_at")),
                            Instant.ofEpochMilli(resultSet.getLong("updated_at")),
                            resultSet.getLong("version"));
            attachment.requireOwnedBy(OwnerReference.of(
                    resultSet.getString("workbench_owner_id"),
                    resultSet.getString("workbench_owner_name")));
            return attachment;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "corrupt uploaded attachment metadata", failure);
        }
    }

    private String requireId(String attachmentId) {
        if (attachmentId == null || attachmentId.trim().isEmpty()
                || attachmentId.length() > 128) {
            throw new IllegalArgumentException(
                    "uploaded attachment id is invalid");
        }
        return attachmentId.trim();
    }
}
