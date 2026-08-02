package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.LocalDeployTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local Deploy API 字段到专用领域 Target 的无分支转换。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class LocalDeployOperationTargetInput
        implements HighImpactOperationTargetInput {

    private final String templateId;
    private final String templateVersion;
    private final String templateHash;
    private final List<String> repositoryTargets;
    private final String expectedWorkspaceStateHash;
    private final String rollbackSummary;

    public LocalDeployOperationTargetInput(
            String templateId, String templateVersion, String templateHash,
            List<String> repositoryTargets,
            String expectedWorkspaceStateHash, String rollbackSummary) {
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.templateHash = templateHash;
        this.repositoryTargets = repositoryTargets == null ? null
                : Collections.unmodifiableList(
                        new ArrayList<String>(repositoryTargets));
        this.expectedWorkspaceStateHash = expectedWorkspaceStateHash;
        this.rollbackSummary = rollbackSummary;
    }

    @Override
    public HighImpactOperationTarget toDomainTarget() {
        return LocalDeployTarget.create(
                templateId, templateVersion, templateHash,
                repositoryTargets, expectedWorkspaceStateHash,
                rollbackSummary);
    }
}
