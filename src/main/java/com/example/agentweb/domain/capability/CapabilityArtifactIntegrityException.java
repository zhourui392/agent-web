package com.example.agentweb.domain.capability;

import lombok.Getter;

/**
 * 不可变 Capability Artifact 冲突、缺失或损坏。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class CapabilityArtifactIntegrityException extends RuntimeException {

    private final String code;

    public CapabilityArtifactIntegrityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CapabilityArtifactIntegrityException(
            String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
