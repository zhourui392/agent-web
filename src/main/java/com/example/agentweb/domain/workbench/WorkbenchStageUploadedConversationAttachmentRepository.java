package com.example.agentweb.domain.workbench;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic Stage 浏览器上传附件聚合的写侧 Repository。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageUploadedConversationAttachmentRepository {

    Optional<WorkbenchStageUploadedConversationAttachment> findById(
            String attachmentId);

    void add(WorkbenchStageUploadedConversationAttachment attachment);

    void update(
            WorkbenchStageUploadedConversationAttachment attachment,
            long expectedVersion);

    List<WorkbenchStageUploadedConversationAttachment> findCleanupCandidates(
            Instant now, int limit);

    void delete(WorkbenchStageUploadedConversationAttachment attachment);
}
