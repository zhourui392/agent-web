package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Git Commit 的精确仓库、基线与文件集合。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class CommitTarget implements HighImpactOperationTarget {

    private final HighImpactOperationType type = HighImpactOperationType.GIT_COMMIT;
    private final String repositoryKey;
    private final String branch;
    private final String expectedHead;
    private final String expectedStateHash;
    private final List<DocumentReference> includedPaths;
    private final String messageHash;
    private final String safeMessagePreview;
    private final String payloadHash;

    private CommitTarget(String repositoryKey, String branch, String expectedHead,
                         String expectedStateHash, List<DocumentReference> includedPaths,
                         String messageHash, String safeMessagePreview) {
        this.repositoryKey = HighImpactTargetSupport.repositoryKey(repositoryKey);
        this.branch = WorkbenchText.requireUntrustedText(branch, "commit branch", 512);
        this.expectedHead = HighImpactTargetSupport.gitObjectId(
                expectedHead, "commit expected HEAD");
        this.expectedStateHash = DomainText.requireSha256(
                expectedStateHash, "commit expected state hash");
        this.includedPaths = validatePaths(includedPaths, this.repositoryKey);
        this.messageHash = DomainText.requireSha256(messageHash, "commit message hash");
        this.safeMessagePreview = WorkbenchText.requireUntrustedText(
                safeMessagePreview, "commit message preview", 500);
        this.payloadHash = HighImpactTargetSupport.payloadHash(
                type.name(), canonical -> appendPayload(canonical));
    }

    public static CommitTarget create(
            String repositoryKey, String branch, String expectedHead,
            String expectedStateHash, List<DocumentReference> includedPaths,
            String messageHash, String safeMessagePreview) {
        return new CommitTarget(
                repositoryKey, branch, expectedHead, expectedStateHash,
                includedPaths, messageHash, safeMessagePreview);
    }

    @Override
    public String requestedPayloadHash() {
        return payloadHash;
    }

    @Override
    public String expectedStateBinding() {
        return expectedStateHash;
    }

    @Override
    public Set<String> repositoryKeys() {
        return Collections.singleton(repositoryKey);
    }

    @Override
    public boolean executionPermanentlyUnavailable() {
        return false;
    }

    private void appendPayload(StringBuilder canonical) {
        CanonicalHashing.appendFramed(canonical, "repositoryKey", repositoryKey);
        CanonicalHashing.appendFramed(canonical, "branch", branch);
        CanonicalHashing.appendFramed(canonical, "expectedHead", expectedHead);
        CanonicalHashing.appendFramed(canonical, "expectedStateHash", expectedStateHash);
        for (DocumentReference path : includedPaths) {
            CanonicalHashing.appendFramed(canonical, "includedPath", path.getRelativePath());
        }
        CanonicalHashing.appendFramed(canonical, "messageHash", messageHash);
    }

    private static List<DocumentReference> validatePaths(
            List<DocumentReference> paths, String repositoryKey) {
        if (paths == null || paths.isEmpty() || paths.contains(null)) {
            throw new IllegalArgumentException(
                    "commit must contain at least one explicit included path");
        }
        List<DocumentReference> ordered = new ArrayList<DocumentReference>(paths);
        Set<DocumentReference> unique = new HashSet<DocumentReference>();
        for (DocumentReference path : ordered) {
            if (!repositoryKey.equals(path.getRepositoryKey())) {
                throw new IllegalArgumentException(
                        "commit included paths must belong to one target repository");
            }
            if (!unique.add(path)) {
                throw new IllegalArgumentException(
                        "commit included paths must not contain duplicates");
            }
        }
        Collections.sort(ordered);
        return Collections.unmodifiableList(ordered);
    }
}
