package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import lombok.Getter;

import java.time.Instant;

/**
 * 上传 API 可公开的最小逻辑附件投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class UploadedConversationAttachmentView {

    private final String attachmentId;
    private final String displayName;
    private final String mediaType;
    private final long size;
    private final String sha256;
    private final Instant expiresAt;

    private UploadedConversationAttachmentView(
            String attachmentId, String displayName, String mediaType,
            long size, String sha256, Instant expiresAt) {
        this.attachmentId = attachmentId;
        this.displayName = displayName;
        this.mediaType = mediaType;
        this.size = size;
        this.sha256 = sha256;
        this.expiresAt = expiresAt;
    }

    public static UploadedConversationAttachmentView from(
            UploadedConversationAttachment attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment projection source is required");
        }
        return new UploadedConversationAttachmentView(
                attachment.getAttachmentId(), attachment.getDisplayName(),
                attachment.getMediaType(), attachment.getSize(),
                attachment.getContentHash(), attachment.getExpiresAt());
    }

    @Override
    public String toString() {
        return "UploadedConversationAttachmentView{attachmentId, displayName, "
                + "mediaType, size, sha256, expiresAt}";
    }
}
