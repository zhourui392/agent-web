package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * 确定性 Gate 使用的 Artifact 元数据与已校验正文组合。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class GateArtifact {

    private final ArtifactDescriptor descriptor;
    private final ArtifactContent content;

    public GateArtifact(ArtifactDescriptor descriptor, ArtifactContent content) {
        if (descriptor == null || content == null) {
            throw new IllegalArgumentException("gate artifact descriptor and content must not be null");
        }
        if (descriptor.getSizeBytes() != content.getSizeBytes()
                || !descriptor.getSha256().equals(content.getSha256())) {
            throw new IllegalArgumentException("gate artifact content does not match descriptor");
        }
        this.descriptor = descriptor;
        this.content = content;
    }

    public ArtifactType getArtifactType() {
        return descriptor.getArtifactType();
    }

    public String text() {
        return new String(content.copyBytes(), StandardCharsets.UTF_8);
    }
}
