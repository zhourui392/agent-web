package com.example.agentweb.app.workbench.operation;

import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperationTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Git Commit API 字段到专用领域 Target 的无分支转换。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class GitCommitOperationTargetInput
        implements HighImpactOperationTargetInput {

    private final String repositoryKey;
    private final String branch;
    private final String expectedHead;
    private final String expectedStateHash;
    private final List<String> includedPaths;
    private final String messageHash;
    private final String safeMessagePreview;

    public GitCommitOperationTargetInput(
            String repositoryKey, String branch, String expectedHead,
            String expectedStateHash, List<String> includedPaths,
            String messageHash, String safeMessagePreview) {
        this.repositoryKey = repositoryKey;
        this.branch = branch;
        this.expectedHead = expectedHead;
        this.expectedStateHash = expectedStateHash;
        this.includedPaths = includedPaths == null ? null
                : Collections.unmodifiableList(
                        new ArrayList<String>(includedPaths));
        this.messageHash = messageHash;
        this.safeMessagePreview = safeMessagePreview;
    }

    @Override
    public HighImpactOperationTarget toDomainTarget() {
        List<DocumentReference> documents =
                new ArrayList<DocumentReference>(includedPaths.size());
        for (String includedPath : includedPaths) {
            documents.add(DocumentReference.of(repositoryKey, includedPath));
        }
        return CommitTarget.create(
                repositoryKey, branch, expectedHead, expectedStateHash,
                documents, messageHash, safeMessagePreview);
    }
}
