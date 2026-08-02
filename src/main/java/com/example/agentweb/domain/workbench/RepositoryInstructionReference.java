package com.example.agentweb.domain.workbench;

import java.util.Objects;

/**
 * 仓库内说明的结构化安全引用，不包含文件正文或绝对路径。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RepositoryInstructionReference {

    private final DocumentReference documentReference;
    private final RepositoryInstructionType type;

    private RepositoryInstructionReference(DocumentReference documentReference,
                                           RepositoryInstructionType type) {
        if (documentReference == null || type == null) {
            throw new IllegalArgumentException(
                    "repository instruction document and type are required");
        }
        this.documentReference = documentReference;
        this.type = type;
    }

    static RepositoryInstructionReference fromDetectedMarker(
            String repositoryKey, RepositoryDevelopmentMarker marker) {
        if (marker == null || !marker.isInstructionReference()) {
            throw new IllegalArgumentException(
                    "repository instruction marker must identify an allowed instruction file");
        }
        return new RepositoryInstructionReference(
                DocumentReference.of(repositoryKey, marker.getRelativePath()),
                marker.getInstructionType());
    }

    public String getRepositoryKey() {
        return documentReference.getRepositoryKey();
    }

    public String getRelativePath() {
        return documentReference.getRelativePath();
    }

    public RepositoryInstructionType getType() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepositoryInstructionReference)) {
            return false;
        }
        RepositoryInstructionReference that = (RepositoryInstructionReference) other;
        return documentReference.equals(that.documentReference) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentReference, type);
    }

    @Override
    public String toString() {
        return type + ":" + documentReference;
    }
}
