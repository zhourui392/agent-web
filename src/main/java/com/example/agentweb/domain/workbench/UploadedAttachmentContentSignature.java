package com.example.agentweb.domain.workbench;

/**
 * 受控临时存储在完整读取正文后给出的内容签名分类。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum UploadedAttachmentContentSignature {
    PNG,
    JPEG,
    GIF,
    WEBP,
    PDF,
    TEXT,
    PE_EXECUTABLE,
    ELF_EXECUTABLE,
    MACHO_EXECUTABLE,
    SHEBANG_EXECUTABLE,
    BINARY_UNKNOWN
}
