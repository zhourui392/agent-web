package com.example.agentweb.domain.harness;

/**
 * local 部署外部动作状态。
 *
 * @author alex
 * @since 2026-07-23
 */
public enum DeploymentExecutionStatus {
    PREPARED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RECONCILIATION_REQUIRED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
