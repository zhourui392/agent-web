package com.example.agentweb.app.workbench;

/**
 * Workbench 不存在或当前 Actor 不可见时的统一应用异常，避免枚举 ID。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchNotFoundException extends RuntimeException {

    public WorkbenchNotFoundException() {
        super("workbench is not available");
    }
}
