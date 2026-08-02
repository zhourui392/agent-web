package com.example.agentweb.app.workbench.attachment;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 上传附件逻辑 ID 的 UUID 生成器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class UuidUploadedAttachmentIdGenerator
        implements UploadedAttachmentIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
