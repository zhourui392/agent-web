package com.example.agentweb.domain.workbench;

/**
 * 人工阶段状态，不表达 Gate、Approval 或正式 PASS。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum WorkbenchPhaseStatus {
    NOT_STARTED,
    IN_PROGRESS,
    HUMAN_COMPLETED
}
