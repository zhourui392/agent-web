package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Agent Runtime 结构化输出中的单个 Artifact，尚未分配持久化版本。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class RuntimeProducedArtifact {

    private final String artifactId;
    private final ArtifactType artifactType;
    private final String contentType;
    private final ArtifactClassification classification;
    private final ArtifactContent content;

    public RuntimeProducedArtifact(String artifactId, ArtifactType artifactType,
                                   String contentType, ArtifactClassification classification,
                                   ArtifactContent content) {
        this.artifactId = DomainText.require(artifactId, "runtime artifact id", 128);
        if (artifactType == null || classification == null || content == null) {
            throw new IllegalArgumentException("runtime artifact metadata and content are required");
        }
        if (content.getSizeBytes() < 1L) {
            throw new IllegalArgumentException("runtime artifact content must not be empty");
        }
        this.artifactType = artifactType;
        this.contentType = requireContentType(contentType);
        this.classification = classification;
        this.content = content;
    }

    private String requireContentType(String value) {
        String normalized = DomainText.require(value, "runtime artifact content type");
        if (!"text/markdown".equals(normalized)
                && !"application/json".equals(normalized)
                && !"text/plain".equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported runtime artifact content type: " + normalized);
        }
        return normalized;
    }
}
