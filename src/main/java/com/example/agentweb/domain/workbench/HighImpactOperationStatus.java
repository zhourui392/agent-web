package com.example.agentweb.domain.workbench;

/**
 * 高影响操作生命周期；AUTHORIZED 只表达人工授权，不等于执行成功。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum HighImpactOperationStatus {
    PROPOSED,
    AUTHORIZED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    RECONCILIATION_REQUIRED,
    REJECTED,
    EXPIRED
}
