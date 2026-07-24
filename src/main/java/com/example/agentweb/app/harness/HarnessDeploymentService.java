package com.example.agentweb.app.harness;

/**
 * Harness local 部署用例。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface HarnessDeploymentService {

    HarnessDeploymentResult start(StartHarnessDeploymentCommand command);

    HarnessDeploymentResult reconcileAsFailed(String runId, String executionId, String reason);
}
