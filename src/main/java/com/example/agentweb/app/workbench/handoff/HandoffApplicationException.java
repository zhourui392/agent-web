package com.example.agentweb.app.workbench.handoff;

import lombok.Getter;

import java.util.Objects;

/**
 * Handoff 应用层资源缺失异常；领域拒绝继续使用 WorkbenchDomainException。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffApplicationException extends IllegalStateException {

    private final HandoffApplicationErrorCode code;
    private final PhaseHandoffProjection current;

    public HandoffApplicationException(
            HandoffApplicationErrorCode code, String message) {
        this(code, message, null);
    }

    public HandoffApplicationException(
            HandoffApplicationErrorCode code, String message,
            PhaseHandoffProjection current) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.current = current;
    }
}
