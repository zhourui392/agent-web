package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 聚合生成、由 Application 原样写入 ArtifactStore 的 Descriptor/Content 对。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class HarnessGeneratedArtifact {

    private final ArtifactDescriptor descriptor;
    private final ArtifactContent content;

    public HarnessGeneratedArtifact(ArtifactDescriptor descriptor, ArtifactContent content) {
        if (descriptor == null || content == null
                || descriptor.getSizeBytes() != content.getSizeBytes()
                || !descriptor.getSha256().equals(content.getSha256())) {
            throw new IllegalArgumentException("generated artifact descriptor and content must match");
        }
        this.descriptor = descriptor;
        this.content = content;
    }
}
