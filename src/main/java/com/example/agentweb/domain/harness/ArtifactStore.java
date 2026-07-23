package com.example.agentweb.domain.harness;

/**
 * Artifact 正文存取端口。Descriptor 与正文 Hash 必须一致，物理路径由 Infrastructure 隐藏。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface ArtifactStore {

    void store(ArtifactDescriptor descriptor, ArtifactContent content);

    ArtifactContent read(ArtifactDescriptor descriptor);
}
