package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Collections;
import java.util.Set;

/**
 * 非 force、非删除 ref 的 Git Push 目标；Push 不继承 Commit 授权。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PushTarget implements HighImpactOperationTarget {

    private final HighImpactOperationType type = HighImpactOperationType.GIT_PUSH;
    private final String repositoryKey;
    private final String remoteName;
    private final String localBranch;
    private final String remoteRef;
    private final String expectedLocalHead;
    private final boolean forceAllowed;
    private final String payloadHash;

    private PushTarget(String repositoryKey, String remoteName, String localBranch,
                       String remoteRef, String expectedLocalHead) {
        this.repositoryKey = HighImpactTargetSupport.repositoryKey(repositoryKey);
        this.remoteName = requireGitToken(remoteName, "push remote name", 128);
        this.localBranch = requireGitToken(localBranch, "push local branch", 512);
        this.remoteRef = requireRemoteBranch(remoteRef);
        this.expectedLocalHead = HighImpactTargetSupport.gitObjectId(
                expectedLocalHead, "push expected local HEAD");
        this.forceAllowed = false;
        this.payloadHash = HighImpactTargetSupport.payloadHash(
                type.name(), canonical -> appendPayload(canonical));
    }

    public static PushTarget create(String repositoryKey, String remoteName,
                                    String localBranch, String remoteRef,
                                    String expectedLocalHead) {
        return new PushTarget(
                repositoryKey, remoteName, localBranch, remoteRef, expectedLocalHead);
    }

    @Override
    public String requestedPayloadHash() {
        return payloadHash;
    }

    @Override
    public String expectedStateBinding() {
        return expectedLocalHead;
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
        CanonicalHashing.appendFramed(canonical, "remoteName", remoteName);
        CanonicalHashing.appendFramed(canonical, "localBranch", localBranch);
        CanonicalHashing.appendFramed(canonical, "remoteRef", remoteRef);
        CanonicalHashing.appendFramed(canonical, "expectedLocalHead", expectedLocalHead);
        CanonicalHashing.appendFramed(canonical, "forceMode", "FORBIDDEN");
    }

    private static String requireGitToken(String value, String name, int maximumLength) {
        String token = DomainText.require(value, name, maximumLength);
        if (token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0
                || token.startsWith("-") || token.indexOf(':') >= 0) {
            throw new IllegalArgumentException(name + " is not a safe Git token");
        }
        return token;
    }

    private static String requireRemoteBranch(String value) {
        String remoteBranch = requireGitToken(value, "push remote ref", 1024);
        if (!remoteBranch.startsWith("refs/heads/")
                || remoteBranch.equals("refs/heads/")) {
            throw new IllegalArgumentException(
                    "push remote ref must identify a non-empty branch");
        }
        return remoteBranch;
    }
}
