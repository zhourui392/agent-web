package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.DeploymentExecutionSpec;
import lombok.Getter;

/**
 * 已提交的 PREPARED 部署及其外部执行规格。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class PreparedHarnessDeployment {

    private final DeploymentExecutionSpec spec;
    private final boolean duplicated;

    public PreparedHarnessDeployment(DeploymentExecutionSpec spec, boolean duplicated) {
        this.spec = spec;
        this.duplicated = duplicated;
    }
}
