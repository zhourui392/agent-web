package com.example.agentweb.app.harness;

/**
 * Run 内部署执行不存在。
 *
 * @author alex
 * @since 2026-07-23
 */
public class HarnessDeploymentExecutionNotFoundException extends RuntimeException {

    public HarnessDeploymentExecutionNotFoundException(String runId, String executionId) {
        super("DeploymentExecution not found: " + runId + "/" + executionId);
    }
}
