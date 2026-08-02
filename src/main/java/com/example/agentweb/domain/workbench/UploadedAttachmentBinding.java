package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Workbench 当前 Phase 会话代际对上传附件的不可变授权边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class UploadedAttachmentBinding {

    private final OwnerReference owner;
    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String conversationId;
    private final int conversationGeneration;

    public UploadedAttachmentBinding(
            OwnerReference owner, WorkbenchId workbenchId,
            WorkbenchPhase phase, String conversationId,
            int conversationGeneration) {
        if (owner == null || workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment binding identity is required");
        }
        if (conversationGeneration < 0) {
            throw new IllegalArgumentException(
                    "uploaded attachment conversation generation must not be negative");
        }
        this.owner = owner;
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.conversationId = DomainText.require(
                conversationId, "uploaded attachment conversation id", 128);
        this.conversationGeneration = conversationGeneration;
    }
}
