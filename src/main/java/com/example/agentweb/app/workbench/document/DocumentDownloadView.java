package com.example.agentweb.app.workbench.document;

import com.example.agentweb.domain.workbench.DocumentReference;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 已稳定读取的文档下载快照，不向 Interface 暴露 live File/Path/Resource。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DocumentDownloadView {

    private final DocumentReference reference;
    private final String fileName;
    private final String mediaType;
    private final long lastModified;
    private final String contentVersion;
    private final byte[] content;

    public DocumentDownloadView(
            DocumentReference reference, String fileName, String mediaType,
            long lastModified, String contentVersion, byte[] content) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.lastModified = lastModified;
        this.contentVersion = Objects.requireNonNull(contentVersion, "contentVersion");
        this.content = Arrays.copyOf(
                Objects.requireNonNull(content, "content"), content.length);
    }

    public long getSize() {
        return content.length;
    }

    public byte[] getContent() {
        return Arrays.copyOf(content, content.length);
    }
}
