package com.example.agentweb.app.workbench.operation;

/**
 * 高影响操作 Owner API 的稳定应用错误。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum OperationApplicationErrorCode {
    WORKBENCH_NOT_FOUND,
    OPERATION_NOT_FOUND,
    VERSION_CONFLICT
}
