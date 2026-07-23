package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Artifact 不可变版本元数据。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class ArtifactDescriptor {

    private final String artifactId;
    private final ArtifactType artifactType;
    private final int version;
    private final String runId;
    private final HarnessStage stage;
    private final int attempt;
    private final String contentType;
    private final long sizeBytes;
    private final String sha256;
    private final ArtifactClassification classification;
    private final String createdBy;
    private final Instant createdAt;
    private final List<ArtifactReference> sourceArtifacts;

    public ArtifactDescriptor(String artifactId, ArtifactType artifactType, int version,
                              String runId, HarnessStage stage, int attempt,
                              String contentType, long sizeBytes, String sha256,
                              ArtifactClassification classification, String createdBy,
                              Instant createdAt, List<ArtifactReference> sourceArtifacts) {
        this.artifactId = DomainText.require(artifactId, "artifact id", 128);
        if (artifactType == null) {
            throw new IllegalArgumentException("artifact type must not be null");
        }
        this.artifactType = artifactType;
        if (version < 1) {
            throw new IllegalArgumentException("artifact version must be positive");
        }
        this.version = version;
        this.runId = DomainText.require(runId, "run id", 128);
        if (stage == null) {
            throw new IllegalArgumentException("artifact stage must not be null");
        }
        this.stage = stage;
        if (attempt < 1) {
            throw new IllegalArgumentException("artifact attempt must be positive");
        }
        this.attempt = attempt;
        this.contentType = requireContentType(contentType);
        if (sizeBytes < 0L) {
            throw new IllegalArgumentException("artifact size must not be negative");
        }
        this.sizeBytes = sizeBytes;
        this.sha256 = DomainText.requireSha256(sha256, "artifact sha256");
        if (classification == null) {
            throw new IllegalArgumentException("artifact classification must not be null");
        }
        this.classification = classification;
        this.createdBy = DomainText.require(createdBy, "artifact creator", 128);
        this.createdAt = DomainText.requireTime(createdAt, "artifact created time");
        this.sourceArtifacts = immutableSources(sourceArtifacts);
    }

    public ArtifactReference reference() {
        return new ArtifactReference(artifactId, version, sha256);
    }

    private String requireContentType(String value) {
        String normalized = DomainText.require(value, "artifact content type");
        if (!"text/markdown".equals(normalized)
                && !"application/json".equals(normalized)
                && !"text/plain".equals(normalized)
                && !"application/octet-stream".equals(normalized)) {
            throw new IllegalArgumentException("unsupported artifact content type: " + normalized);
        }
        return normalized;
    }

    private List<ArtifactReference> immutableSources(List<ArtifactReference> values) {
        if (values == null) {
            throw new IllegalArgumentException("source artifacts must not be null");
        }
        List<ArtifactReference> copy = new ArrayList<ArtifactReference>(values.size());
        java.util.Set<String> unique = new java.util.HashSet<String>();
        for (ArtifactReference value : values) {
            if (value == null) {
                throw new IllegalArgumentException("source artifact must not be null");
            }
            String identity = value.getArtifactId() + ":" + value.getVersion() + ":" + value.getSha256();
            if (!unique.add(identity)) {
                throw new IllegalArgumentException("source artifacts must be unique");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}
