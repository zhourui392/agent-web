package com.example.agentweb.domain.capability;

import lombok.Getter;

/**
 * Capability Source Configuration 乐观版本冲突。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public class CapabilitySourceVersionConflictException extends RuntimeException {

    private final String code = "WORKBENCH_CAPABILITY_SOURCE_VERSION_CONFLICT";

    public CapabilitySourceVersionConflictException(long expectedVersion, long actualVersion) {
        super("capability source version conflict: expected " + expectedVersion
                + " but was " + actualVersion);
    }
}
