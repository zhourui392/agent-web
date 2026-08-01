package com.example.agentweb.infra.workspace.document;

import lombok.Getter;

/**
 * 有界 UTF-8 文本预览。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
final class DocumentTextPreview {

    private final String content;
    private final boolean truncated;

    DocumentTextPreview(String content, boolean truncated) {
        this.content = content;
        this.truncated = truncated;
    }
}
