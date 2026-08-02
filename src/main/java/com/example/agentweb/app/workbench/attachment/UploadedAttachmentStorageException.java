package com.example.agentweb.app.workbench.attachment;

/**
 * 受控上传存储失败；消息有意不携带物理路径。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class UploadedAttachmentStorageException
        extends IllegalStateException {

    public UploadedAttachmentStorageException(String message) {
        super(message);
    }

    public UploadedAttachmentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
