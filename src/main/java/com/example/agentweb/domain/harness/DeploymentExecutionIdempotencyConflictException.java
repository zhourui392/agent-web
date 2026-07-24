package com.example.agentweb.domain.harness;

/**
 * 部署幂等键被不同输入基线或模板复用。
 *
 * @author alex
 * @since 2026-07-23
 */
public class DeploymentExecutionIdempotencyConflictException extends IllegalStateException {

    public DeploymentExecutionIdempotencyConflictException() {
        super("deployment idempotency key belongs to a different request");
    }
}
