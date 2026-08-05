package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Dynamic Stage 当前会话代际对上传附件的不可变授权边界。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class WorkbenchStageUploadedAttachmentBinding {

    private final OwnerReference owner;
    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final String conversationId;
    private final int conversationGeneration;

    public WorkbenchStageUploadedAttachmentBinding(
            OwnerReference owner, WorkbenchId workbenchId,
            String stageInstanceIdentifier, String conversationId,
            int conversationGeneration) {
        if (owner == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment binding identity is required");
        }
        if (conversationGeneration < 0) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment generation must not be negative");
        }
        this.owner = owner;
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = DomainText.require(
                stageInstanceIdentifier,
                "Stage uploaded attachment instance identifier", 128);
        this.conversationId = DomainText.require(
                conversationId,
                "Stage uploaded attachment conversation identifier", 128);
        this.conversationGeneration = conversationGeneration;
    }
}
