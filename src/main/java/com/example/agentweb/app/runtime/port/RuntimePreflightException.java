package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.util.Objects;

/**
 * Runtime Preflight fail-closed 异常；公开消息必须是稳定安全摘要。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimePreflightException extends RuntimeException {

    private final RuntimePreflightErrorCode errorCode;

    public RuntimePreflightException(
            RuntimePreflightErrorCode errorCode, String safeMessage) {
        super(requireSafeMessage(safeMessage));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public RuntimePreflightException(
            RuntimePreflightErrorCode errorCode,
            String safeMessage, Throwable cause) {
        super(requireSafeMessage(safeMessage), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    private static String requireSafeMessage(String safeMessage) {
        if (safeMessage == null || safeMessage.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "runtime preflight safe message must not be blank");
        }
        return safeMessage;
    }
}
