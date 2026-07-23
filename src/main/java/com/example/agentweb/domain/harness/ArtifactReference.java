package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 对不可变 Artifact 版本的引用。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class ArtifactReference {

    private final String artifactId;
    private final int version;
    private final String sha256;

    public ArtifactReference(String artifactId, int version, String sha256) {
        this.artifactId = DomainText.require(artifactId, "artifact id", 128);
        if (version < 1) {
            throw new IllegalArgumentException("artifact version must be positive");
        }
        this.version = version;
        this.sha256 = DomainText.requireSha256(sha256, "artifact sha256");
    }
}
