package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.HighImpactOperationTarget;
import com.example.agentweb.domain.workbench.PushTarget;

/**
 * Git Push API 字段到专用领域 Target 的无分支转换。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class GitPushOperationTargetInput
        implements HighImpactOperationTargetInput {

    private final String repositoryKey;
    private final String remoteName;
    private final String localBranch;
    private final String remoteRef;
    private final String expectedLocalHead;

    public GitPushOperationTargetInput(
            String repositoryKey, String remoteName, String localBranch,
            String remoteRef, String expectedLocalHead) {
        this.repositoryKey = repositoryKey;
        this.remoteName = remoteName;
        this.localBranch = localBranch;
        this.remoteRef = remoteRef;
        this.expectedLocalHead = expectedLocalHead;
    }

    @Override
    public HighImpactOperationTarget toDomainTarget() {
        return PushTarget.create(
                repositoryKey, remoteName, localBranch,
                remoteRef, expectedLocalHead);
    }
}
