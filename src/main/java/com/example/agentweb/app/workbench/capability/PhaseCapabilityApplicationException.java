package com.example.agentweb.app.workbench.capability;

import lombok.Getter;

import java.util.Objects;

/**
 * Phase Capability 应用层资源生命周期异常。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityApplicationException
        extends IllegalStateException {

    private final PhaseCapabilityApplicationErrorCode code;

    public PhaseCapabilityApplicationException(
            PhaseCapabilityApplicationErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }
}
