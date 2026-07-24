package com.example.agentweb.domain.harness;

/**
 * 管理员模板允许的 local 部署逻辑步骤；ROLLBACK 首版只保存不自动执行。
 *
 * @author alex
 * @since 2026-07-23
 */
public enum DeploymentStep {
    BUILD,
    DEPLOY,
    HEALTH_CHECK,
    ACCEPTANCE,
    ROLLBACK
}
