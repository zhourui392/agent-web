package com.example.agentweb.app.workbench.operation;

import lombok.Getter;

/**
 * 高影响操作 Owner-safe 应用异常；可选 current 仅包含安全投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class OperationApplicationException extends RuntimeException {

    private final OperationApplicationErrorCode code;
    private final HighImpactOperationProjection current;

    public OperationApplicationException(
            OperationApplicationErrorCode code, String message) {
        this(code, message, null);
    }

    public OperationApplicationException(
            OperationApplicationErrorCode code, String message,
            HighImpactOperationProjection current) {
        super(message);
        if (code == null) {
            throw new IllegalArgumentException(
                    "operation application error code must not be null");
        }
        this.code = code;
        this.current = current;
    }
}
