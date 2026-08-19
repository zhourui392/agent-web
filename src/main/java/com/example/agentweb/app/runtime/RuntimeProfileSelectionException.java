package com.example.agentweb.app.runtime;

/**
 * Profile 选择失败；首期统一映射为 RUNTIME_PROFILE_NOT_FOUND。
 *
 * @author alex
 * @since 2026-08-07
 */
public class RuntimeProfileSelectionException extends RuntimeException {

    public RuntimeProfileSelectionException(String message) {
        super(message);
    }
}
