package com.example.agentweb.app.harness;

import java.util.List;
import java.util.Optional;

/**
 * 部署执行 CQRS 查询端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface DeploymentExecutionQueryService {

    Optional<DeploymentExecutionView> find(String runId, String executionId);

    List<DeploymentExecutionView> listByRun(String runId);
}
