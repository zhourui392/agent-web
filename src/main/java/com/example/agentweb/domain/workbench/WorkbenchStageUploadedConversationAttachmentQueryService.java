package com.example.agentweb.domain.workbench;

import java.time.Instant;

/**
 * Dynamic Stage 浏览器上传附件的只读配额投影。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageUploadedConversationAttachmentQueryService {

    long countAvailable(
            WorkbenchStageUploadedAttachmentBinding binding, Instant now);
}
