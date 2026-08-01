package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 单仓库在观察时刻的 branch、HEAD、clean、diff Hash 和文件状态。
 *
 * <p>语义对齐现有 {@link WorkspaceBaseline}，增加 repositoryKey 维度以支持多仓库。
 * 改名与语义升级分两提交：本类型为新类型，旧 {@code WorkspaceBaseline} 暂保留。
 *
 * @author zhourui(V33215020)
 * @since 2026-08-01
 */
@Getter
public final class RepositoryBaseline {

    private static final Pattern COMMIT = Pattern.compile("[a-f0-9]{40}|[a-f0-9]{64}");

    private final String repositoryKey;
    private final String repositoryRoot;
    private final String branch;
    private final String head;
    private final boolean clean;
    private final String diffHash;
    private final List<ChangedFileEvidence> files;
    private final Instant capturedAt;

    private RepositoryBaseline(String repositoryKey, String repositoryRoot, String branch,
                               String head, boolean clean, String diffHash,
                               List<ChangedFileEvidence> files, Instant capturedAt) {
        this.repositoryKey = RepositorySelection.normalizeRepositoryKey(repositoryKey);
        this.repositoryRoot = DomainText.require(repositoryRoot, "repository root", 4096)
                .replace('\\', '/');
        this.branch = DomainText.require(branch, "repository branch", 512);
        String normalizedHead = DomainText.require(head, "repository head", 64).toLowerCase();
        if (!COMMIT.matcher(normalizedHead).matches()) {
            throw new IllegalArgumentException("repository head must be a Git object id");
        }
        this.head = normalizedHead;
        this.clean = clean;
        this.diffHash = DomainText.requireSha256(diffHash, "repository diff hash");
        if (files == null || files.contains(null)) {
            throw new IllegalArgumentException("repository changed files must not be null");
        }
        List<ChangedFileEvidence> ordered = new ArrayList<ChangedFileEvidence>(files);
        ordered.sort(java.util.Comparator.comparing(ChangedFileEvidence::getPath));
        Set<String> paths = new HashSet<String>();
        for (ChangedFileEvidence file : ordered) {
            if (!paths.add(file.getPath())) {
                throw new IllegalArgumentException(
                        "repository changed file paths must be unique within " + this.repositoryKey);
            }
        }
        if (clean && !ordered.isEmpty()) {
            throw new IllegalArgumentException(
                    "repository clean state and changed files disagree for " + this.repositoryKey);
        }
        this.files = Collections.unmodifiableList(ordered);
        this.capturedAt = DomainText.requireTime(capturedAt, "repository baseline captured time");
    }

    public static RepositoryBaseline capture(String repositoryKey, String repositoryRoot,
                                             String branch, String head, boolean clean,
                                             String diffHash, Instant capturedAt) {
        return new RepositoryBaseline(repositoryKey, repositoryRoot, branch, head, clean, diffHash,
                Collections.<ChangedFileEvidence>emptyList(), capturedAt);
    }

    public static RepositoryBaseline capture(String repositoryKey, String repositoryRoot,
                                             String branch, String head, boolean clean,
                                             String diffHash, List<ChangedFileEvidence> files,
                                             Instant capturedAt) {
        return new RepositoryBaseline(repositoryKey, repositoryRoot, branch, head, clean, diffHash,
                files, capturedAt);
    }

    /**
     * 从现有单仓库 {@link WorkspaceBaseline} 适配为 N=1 的 RepositoryBaseline。
     */
    public static RepositoryBaseline fromLegacy(String repositoryKey, WorkspaceBaseline baseline) {
        if (baseline == null) {
            throw new IllegalArgumentException("workspace baseline must not be null");
        }
        return new RepositoryBaseline(repositoryKey, baseline.getRepositoryRoot(),
                baseline.getBranch(), baseline.getHead(), baseline.isClean(),
                baseline.getDiffHash(), baseline.getFiles(), baseline.getCapturedAt());
    }

    public boolean sameRepositoryState(RepositoryBaseline other) {
        return other != null
                && repositoryKey.equals(other.repositoryKey)
                && branch.equals(other.branch)
                && head.equals(other.head)
                && clean == other.clean
                && diffHash.equals(other.diffHash);
    }

    public boolean belongsToSameRepository(RepositoryBaseline other) {
        return other != null
                && repositoryKey.equals(other.repositoryKey)
                && repositoryRoot.equals(other.repositoryRoot)
                && branch.equals(other.branch);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepositoryBaseline)) {
            return false;
        }
        RepositoryBaseline that = (RepositoryBaseline) other;
        return clean == that.clean
                && repositoryKey.equals(that.repositoryKey)
                && repositoryRoot.equals(that.repositoryRoot)
                && branch.equals(that.branch)
                && head.equals(that.head)
                && diffHash.equals(that.diffHash)
                && files.equals(that.files)
                && capturedAt.equals(that.capturedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryKey, repositoryRoot, branch, head, clean, diffHash,
                files, capturedAt);
    }
}
