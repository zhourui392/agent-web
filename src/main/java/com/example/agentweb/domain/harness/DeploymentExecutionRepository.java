package com.example.agentweb.domain.harness;

import java.util.List;
import java.util.Optional;

/**
 * 部署执行聚合写侧仓储。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface DeploymentExecutionRepository {

    void add(DeploymentExecution execution);

    void update(DeploymentExecution execution);

    Optional<DeploymentExecution> findById(String executionId);

    Optional<DeploymentExecution> findByIdempotencyKey(String runId, String idempotencyKey);

    List<DeploymentExecution> findUnfinished();
}
