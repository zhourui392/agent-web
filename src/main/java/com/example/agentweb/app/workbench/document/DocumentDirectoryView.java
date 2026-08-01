package com.example.agentweb.app.workbench.document;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Workbench 文档目录的有界只读投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DocumentDirectoryView {

    private final String repositoryKey;
    private final String path;
    private final List<DocumentDirectoryEntryView> entries;
    private final boolean truncated;

    public DocumentDirectoryView(
            String repositoryKey, String path,
            List<DocumentDirectoryEntryView> entries, boolean truncated) {
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        this.path = Objects.requireNonNull(path, "path");
        this.entries = Collections.unmodifiableList(new ArrayList<DocumentDirectoryEntryView>(
                Objects.requireNonNull(entries, "entries")));
        this.truncated = truncated;
    }
}
