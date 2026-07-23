package com.example.agentweb.domain.harness;

/**
 * Artifact 正文存取、Hash 校验或原子落盘失败。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class ArtifactStoreException extends RuntimeException {

    public ArtifactStoreException(String message) {
        super(message);
    }

    public ArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
