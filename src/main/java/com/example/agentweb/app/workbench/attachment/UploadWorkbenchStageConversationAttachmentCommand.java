package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.WorkbenchId;
import lombok.Getter;

/**
 * Dynamic Stage multipart 边界规范化后的上传附件命令。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class UploadWorkbenchStageConversationAttachmentCommand {

    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final int conversationGeneration;
    private final String displayName;
    private final String clientMediaType;
    private final long declaredSize;

    public UploadWorkbenchStageConversationAttachmentCommand(
            WorkbenchId workbenchId, String stageInstanceIdentifier,
            int conversationGeneration, String displayName,
            String clientMediaType, long declaredSize) {
        if (workbenchId == null) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment Workbench is required");
        }
        if (conversationGeneration < 0 || declaredSize < 0L) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment generation and size are invalid");
        }
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = DomainText.require(
                stageInstanceIdentifier,
                "Stage uploaded attachment instance identifier", 128);
        this.conversationGeneration = conversationGeneration;
        this.displayName = displayName;
        this.clientMediaType = clientMediaType;
        this.declaredSize = declaredSize;
    }
}
