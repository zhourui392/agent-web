package com.example.agentweb.app.workbench.document;

import lombok.Getter;

import java.util.Objects;

/**
 * 文档树中的安全相对路径投影，不包含服务端文件系统对象。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DocumentDirectoryEntryView {

    private final String name;
    private final String relativePath;
    private final DocumentEntryKind kind;
    private final Long size;
    private final long lastModified;

    public DocumentDirectoryEntryView(
            String name, String relativePath, DocumentEntryKind kind,
            Long size, long lastModified) {
        this.name = Objects.requireNonNull(name, "name");
        this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.size = size;
        this.lastModified = lastModified;
    }
}
