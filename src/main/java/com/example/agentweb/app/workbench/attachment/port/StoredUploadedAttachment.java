package com.example.agentweb.app.workbench.attachment.port;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import lombok.Getter;

/**
 * 临时存储完整写入并检查后的私有事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class StoredUploadedAttachment {

    private final String storageKey;
    private final String sha256;
    private final long size;
    private final UploadedAttachmentContentSignature contentSignature;

    public StoredUploadedAttachment(
            String storageKey, String sha256, long size,
            UploadedAttachmentContentSignature contentSignature) {
        this.storageKey = DomainText.requireSha256(
                storageKey, "uploaded attachment storage key");
        this.sha256 = DomainText.requireSha256(
                sha256, "uploaded attachment content hash");
        if (size < 1L || contentSignature == null) {
            throw new IllegalArgumentException(
                    "stored uploaded attachment facts are invalid");
        }
        this.size = size;
        this.contentSignature = contentSignature;
    }

    @Override
    public String toString() {
        return "StoredUploadedAttachment{sha256, size, contentSignature}";
    }
}
