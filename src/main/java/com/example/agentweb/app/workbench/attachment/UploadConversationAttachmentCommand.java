package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * multipart 边界规范化后的上传附件命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class UploadConversationAttachmentCommand {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final int conversationGeneration;
    private final String displayName;
    private final String clientMediaType;
    private final long declaredSize;

    public UploadConversationAttachmentCommand(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            int conversationGeneration, String displayName,
            String clientMediaType, long declaredSize) {
        if (workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment workbench and phase are required");
        }
        if (conversationGeneration < 0 || declaredSize < 0L) {
            throw new IllegalArgumentException(
                    "uploaded attachment generation and size are invalid");
        }
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.conversationGeneration = conversationGeneration;
        this.displayName = displayName;
        this.clientMediaType = clientMediaType;
        this.declaredSize = declaredSize;
    }
}
