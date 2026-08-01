package com.example.agentweb.app.workbench.document;

import com.example.agentweb.domain.workbench.DocumentReference;
import lombok.Getter;

import java.util.Objects;

/**
 * Workbench 文档内容或二进制元数据的只读投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DocumentContentView {

    private final DocumentReference reference;
    private final DocumentKind kind;
    private final String mediaType;
    private final String encoding;
    private final long size;
    private final long lastModified;
    private final String contentVersion;
    private final String content;
    private final boolean truncated;
    private final boolean deleted;

    public DocumentContentView(
            DocumentReference reference, DocumentKind kind, String mediaType,
            String encoding, long size, long lastModified, String contentVersion,
            String content, boolean truncated, boolean deleted) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.encoding = encoding;
        this.size = size;
        this.lastModified = lastModified;
        this.contentVersion = Objects.requireNonNull(contentVersion, "contentVersion");
        this.content = content;
        this.truncated = truncated;
        this.deleted = deleted;
    }
}
