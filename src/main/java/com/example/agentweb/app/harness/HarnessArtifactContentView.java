package com.example.agentweb.app.harness;

import lombok.Getter;

import java.util.Arrays;

/**
 * Artifact 下载读模型。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class HarnessArtifactContentView {

    private final String artifactId;
    private final int version;
    private final String artifactType;
    private final String contentType;
    private final String sha256;
    private final byte[] content;

    public HarnessArtifactContentView(String artifactId, int version, String artifactType,
                                      String contentType, String sha256, byte[] content) {
        this.artifactId = artifactId;
        this.version = version;
        this.artifactType = artifactType;
        this.contentType = contentType;
        this.sha256 = sha256;
        this.content = Arrays.copyOf(content, content.length);
    }

    public byte[] copyContent() {
        return Arrays.copyOf(content, content.length);
    }

    public byte[] getContent() {
        return copyContent();
    }
}
