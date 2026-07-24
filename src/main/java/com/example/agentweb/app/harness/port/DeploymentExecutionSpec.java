package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.DeploymentCommandTemplate;
import lombok.Getter;

/**
 * 已提交 PREPARED 记录后交给部署 Gateway 的受控规格。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentExecutionSpec {

    private final String executionId;
    private final String runId;
    private final String workingDir;
    private final DeploymentCommandTemplate template;

    public DeploymentExecutionSpec(String executionId, String runId, String workingDir,
                                   DeploymentCommandTemplate template) {
        if (executionId == null || executionId.trim().isEmpty()
                || runId == null || runId.trim().isEmpty()
                || workingDir == null || workingDir.trim().isEmpty() || template == null) {
            throw new IllegalArgumentException("deployment execution spec is incomplete");
        }
        this.executionId = executionId.trim();
        this.runId = runId.trim();
        this.workingDir = workingDir.trim();
        this.template = template;
    }
}
