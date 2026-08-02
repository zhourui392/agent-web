package com.example.agentweb.app.workbench.attachment.port;

import lombok.Getter;

import java.io.InputStream;

/**
 * 受控临时存储的有界输入请求。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class UploadedAttachmentStorageRequest {

    private final InputStream inputStream;
    private final long declaredSize;

    public UploadedAttachmentStorageRequest(
            InputStream inputStream, long declaredSize) {
        if (inputStream == null || declaredSize < 0L) {
            throw new IllegalArgumentException(
                    "uploaded attachment storage request is invalid");
        }
        this.inputStream = inputStream;
        this.declaredSize = declaredSize;
    }
}
