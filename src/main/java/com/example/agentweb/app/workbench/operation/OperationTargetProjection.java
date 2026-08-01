package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.LocalDeployTarget;
import com.example.agentweb.domain.workbench.ProductionWriteTarget;
import com.example.agentweb.domain.workbench.PushTarget;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不含 Credential、命令正文和绝对路径的类型化操作目标投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class OperationTargetProjection {

    private final HighImpactOperationType type;
    private final List<String> repositoryKeys;
    private final Map<String, Object> details;

    private OperationTargetProjection(
            HighImpactOperationType type, List<String> repositoryKeys,
            Map<String, Object> details) {
        this.type = type;
        this.repositoryKeys = Collections.unmodifiableList(
                new ArrayList<String>(repositoryKeys));
        this.details = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(details));
    }

    public static OperationTargetProjection from(
            HighImpactOperationTarget target) {
        if (target == null) {
            throw new IllegalArgumentException(
                    "operation target must not be null");
        }
        List<String> repositories = new ArrayList<String>(target.repositoryKeys());
        Collections.sort(repositories);
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        if (target instanceof CommitTarget) {
            appendCommit(details, (CommitTarget) target);
        } else if (target instanceof PushTarget) {
            appendPush(details, (PushTarget) target);
        } else if (target instanceof LocalDeployTarget) {
            appendDeploy(details, (LocalDeployTarget) target);
        } else if (target instanceof ProductionWriteTarget) {
            appendProduction(details, (ProductionWriteTarget) target);
        } else {
            throw new IllegalArgumentException(
                    "unsupported high-impact operation target type");
        }
        return new OperationTargetProjection(target.getType(), repositories, details);
    }

    private static void appendCommit(
            Map<String, Object> details, CommitTarget target) {
        details.put("branch", target.getBranch());
        details.put("expectedHead", target.getExpectedHead());
        details.put("expectedStateHash", target.getExpectedStateHash());
        List<String> includedPaths = new ArrayList<String>();
        for (DocumentReference path : target.getIncludedPaths()) {
            includedPaths.add(path.getRelativePath());
        }
        details.put("includedPaths", Collections.unmodifiableList(includedPaths));
        details.put("messageHash", target.getMessageHash());
        details.put("safeMessagePreview", target.getSafeMessagePreview());
    }

    private static void appendPush(
            Map<String, Object> details, PushTarget target) {
        details.put("remoteName", target.getRemoteName());
        details.put("localBranch", target.getLocalBranch());
        details.put("remoteRef", target.getRemoteRef());
        details.put("expectedLocalHead", target.getExpectedLocalHead());
        details.put("forceAllowed", Boolean.valueOf(target.isForceAllowed()));
    }

    private static void appendDeploy(
            Map<String, Object> details, LocalDeployTarget target) {
        details.put("templateId", target.getTemplateId());
        details.put("templateVersion", target.getTemplateVersion());
        details.put("templateHash", target.getTemplateHash());
        details.put("environment", target.getEnvironment().name());
        details.put("expectedWorkspaceStateHash",
                target.getExpectedWorkspaceStateHash());
        details.put("rollbackSummary", target.getRollbackSummary());
    }

    private static void appendProduction(
            Map<String, Object> details, ProductionWriteTarget target) {
        details.put("environment", target.getEnvironment());
        details.put("resourceReference", target.getResourceReference());
        details.put("expectedProductionStateHash",
                target.getExpectedProductionStateHash());
    }
}
