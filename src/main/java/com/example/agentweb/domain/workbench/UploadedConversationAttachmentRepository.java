package com.example.agentweb.domain.workbench;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 浏览器上传附件聚合的写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface UploadedConversationAttachmentRepository {

    Optional<UploadedConversationAttachment> findById(String attachmentId);

    void add(UploadedConversationAttachment attachment);

    void update(UploadedConversationAttachment attachment, long expectedVersion);

    List<UploadedConversationAttachment> findCleanupCandidates(
            Instant now, int limit);

    void delete(UploadedConversationAttachment attachment);
}
