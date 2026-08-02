package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;

/**
 * 上传附件在 Run 准备期重新绑定后冻结的逻辑与私有存储事实。
 *
 * <p>storageKey 仅供持久化 Snapshot 和 Runtime 私有计划使用，不能进入 HTTP/SSE/日志。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class VerifiedUploadedConversationAttachment {

    private final String attachmentId;
    private final UploadedAttachmentBinding binding;
    private final String displayName;
    private final String mediaType;
    private final long size;
    private final String contentHash;
    private final String storageKey;
    private final String runtimeFileName;
    private final Instant expiresAt;
    private final long attachmentVersion;

    private VerifiedUploadedConversationAttachment(
            String attachmentId, UploadedAttachmentBinding binding,
            String displayName, String mediaType, long size,
            String contentHash, String storageKey, String runtimeFileName,
            Instant expiresAt, long attachmentVersion) {
        this.attachmentId = DomainText.require(
                attachmentId, "uploaded attachment id", 128);
        if (binding == null || size < 1L || attachmentVersion < 0L) {
            throw new IllegalArgumentException(
                    "verified uploaded attachment facts are invalid");
        }
        this.binding = binding;
        this.displayName = DomainText.require(
                displayName, "uploaded attachment display name", 255);
        this.mediaType = DomainText.require(
                mediaType, "uploaded attachment media type", 160);
        this.size = size;
        this.contentHash = DomainText.requireSha256(
                contentHash, "uploaded attachment content hash");
        this.storageKey = DomainText.requireSha256(
                storageKey, "uploaded attachment storage key");
        this.runtimeFileName = requireRuntimeFileName(runtimeFileName);
        this.expiresAt = DomainText.requireTime(
                expiresAt, "uploaded attachment expiry");
        this.attachmentVersion = attachmentVersion;
    }

    static VerifiedUploadedConversationAttachment from(
            UploadedConversationAttachment attachment) {
        return new VerifiedUploadedConversationAttachment(
                attachment.getAttachmentId(), attachment.binding(),
                attachment.getDisplayName(), attachment.getMediaType(),
                attachment.getSize(), attachment.getContentHash(),
                attachment.getStorageKey(),
                runtimeFileName(
                        attachment.getAttachmentId(),
                        attachment.getDisplayName()),
                attachment.getExpiresAt(), attachment.getVersion());
    }

    public static VerifiedUploadedConversationAttachment restore(
            String attachmentId, UploadedAttachmentBinding binding,
            String displayName, String mediaType, long size,
            String contentHash, String storageKey, String runtimeFileName,
            Instant expiresAt, long attachmentVersion) {
        return new VerifiedUploadedConversationAttachment(
                attachmentId, binding, displayName, mediaType, size,
                contentHash, storageKey, runtimeFileName, expiresAt,
                attachmentVersion);
    }

    public String runtimeReference() {
        return "$AGENT_WORKBENCH_ATTACHMENT_DIR/" + runtimeFileName;
    }

    public String logicalIdentity() {
        return "UPLOADED_CONVERSATION:" + attachmentId;
    }

    public void requireBinding(UploadedAttachmentBinding expectedBinding) {
        if (!binding.equals(expectedBinding)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    void requireExactAttachment(UploadedConversationAttachment attachment) {
        if (attachment == null
                || !attachmentId.equals(attachment.getAttachmentId())
                || !binding.equals(attachment.binding())
                || !displayName.equals(attachment.getDisplayName())
                || !mediaType.equals(attachment.getMediaType())
                || size != attachment.getSize()
                || !contentHash.equals(attachment.getContentHash())
                || !storageKey.equals(attachment.getStorageKey())
                || !expiresAt.equals(attachment.getExpiresAt())
                || attachmentVersion != attachment.getVersion()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                    "uploaded attachment changed after run preparation");
        }
    }

    private static String runtimeFileName(
            String attachmentId, String displayName) {
        int lastDot = displayName.lastIndexOf('.');
        String extension = lastDot < 0
                ? "" : displayName.substring(lastDot).toLowerCase(java.util.Locale.ROOT);
        return "attachment-"
                + CanonicalHashing.sha256(attachmentId).substring(0, 20)
                + extension;
    }

    private static String requireRuntimeFileName(String value) {
        String normalized = DomainText.require(
                value, "uploaded attachment runtime file name", 128);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]*")
                || normalized.contains("..")) {
            throw new IllegalArgumentException(
                    "uploaded attachment runtime file name is invalid");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "VerifiedUploadedConversationAttachment{attachmentId, displayName, "
                + "mediaType, size, contentHash, runtimeFileName, expiresAt}";
    }
}
