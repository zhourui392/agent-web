package com.example.agentweb.domain.workbench;

import java.time.Instant;

/**
 * 浏览器上传附件的只读配额投影。
 *
 * @author alex
 * @since 2026-08-02
 */
public interface UploadedConversationAttachmentQueryService {

    long countAvailable(UploadedAttachmentBinding binding, Instant now);
}
