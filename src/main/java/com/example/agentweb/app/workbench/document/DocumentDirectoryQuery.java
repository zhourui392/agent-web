package com.example.agentweb.app.workbench.document;

import com.example.agentweb.domain.workbench.DocumentReference;
import lombok.Getter;

/**
 * Workbench Repository Scope 内的有界目录查询。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DocumentDirectoryQuery {

    public static final int MAXIMUM_LIMIT = 1000;

    private final String repositoryKey;
    private final String relativePath;
    private final int limit;

    public DocumentDirectoryQuery(
            String repositoryKey, String relativePath, int limit) {
        this.repositoryKey = DocumentReference.requireRepositoryKey(repositoryKey);
        if (relativePath == null) {
            throw new IllegalArgumentException(
                    "document directory relative path must not be null");
        }
        this.relativePath = relativePath.isEmpty()
                ? "" : DocumentReference.requireRelativePath(relativePath);
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    "document directory limit must be between 1 and " + MAXIMUM_LIMIT);
        }
        this.limit = limit;
    }
}
