package com.example.agentweb.app.workbench.attachment;

/**
 * 上传附件周期清理的应用层批处理参数。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class UploadedAttachmentCleanupSettings {

    private final int batchSize;

    public UploadedAttachmentCleanupSettings(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
