package com.example.agentweb.domain.harness;

/**
 * Artifact 正文写入前的基础 Secret 脱敏端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface ArtifactContentSanitizer {

    ArtifactContent sanitize(String contentType, ArtifactContent content);
}
