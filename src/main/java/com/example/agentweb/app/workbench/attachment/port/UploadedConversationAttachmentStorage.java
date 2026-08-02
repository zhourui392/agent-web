package com.example.agentweb.app.workbench.attachment.port;

import java.nio.file.Path;

/**
 * 浏览器上传附件的受控临时存储与 Runtime 复制端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface UploadedConversationAttachmentStorage {

    StoredUploadedAttachment store(UploadedAttachmentStorageRequest request);

    void copyVerified(
            String storageKey, Path destination,
            String expectedSha256, long expectedSize);

    void delete(String storageKey);
}
