package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Run 创建时固定的 Git 工作区基线，用于区分用户原有变更与本次实现 Diff。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class WorkspaceBaseline {

    private static final Pattern COMMIT = Pattern.compile("[a-f0-9]{40}|[a-f0-9]{64}");

    private final String repositoryRoot;
    private final String branch;
    private final String head;
    private final boolean clean;
    private final String diffHash;
    private final List<ChangedFileEvidence> files;
    private final Instant capturedAt;

    private WorkspaceBaseline(String repositoryRoot, String branch, String head,
                              boolean clean, String diffHash, List<ChangedFileEvidence> files,
                              Instant capturedAt) {
        this.repositoryRoot = DomainText.require(repositoryRoot, "workspace repository root");
        this.branch = DomainText.require(branch, "workspace branch", 512);
        String normalizedHead = DomainText.require(head, "workspace head", 64).toLowerCase();
        if (!COMMIT.matcher(normalizedHead).matches()) {
            throw new IllegalArgumentException("workspace head must be a Git object id");
        }
        this.head = normalizedHead;
        this.clean = clean;
        this.diffHash = DomainText.requireSha256(diffHash, "workspace diff hash");
        if (files == null || files.contains(null)) {
            throw new IllegalArgumentException("workspace changed files must not be null");
        }
        List<ChangedFileEvidence> ordered = new ArrayList<ChangedFileEvidence>(files);
        ordered.sort(java.util.Comparator.comparing(ChangedFileEvidence::getPath));
        Set<String> paths = new HashSet<String>();
        for (ChangedFileEvidence file : ordered) {
            if (!paths.add(file.getPath())) {
                throw new IllegalArgumentException("workspace changed file paths must be unique");
            }
        }
        if (clean && !ordered.isEmpty()) {
            throw new IllegalArgumentException("workspace clean state and changed files disagree");
        }
        this.files = Collections.unmodifiableList(ordered);
        this.capturedAt = DomainText.requireTime(capturedAt, "workspace baseline captured time");
    }

    public static WorkspaceBaseline capture(String repositoryRoot, String branch, String head,
                                            boolean clean, String diffHash, Instant capturedAt) {
        return new WorkspaceBaseline(repositoryRoot, branch, head, clean, diffHash,
                Collections.<ChangedFileEvidence>emptyList(), capturedAt);
    }

    public static WorkspaceBaseline capture(String repositoryRoot, String branch, String head,
                                            boolean clean, String diffHash,
                                            List<ChangedFileEvidence> files, Instant capturedAt) {
        return new WorkspaceBaseline(repositoryRoot, branch, head, clean, diffHash,
                files, capturedAt);
    }

    static WorkspaceBaseline legacy(String workingDir, Instant capturedAt) {
        return new WorkspaceBaseline(workingDir, "UNKNOWN",
                String.join("", java.util.Collections.nCopies(40, "0")), false,
                String.join("", java.util.Collections.nCopies(64, "0")),
                Collections.<ChangedFileEvidence>emptyList(), capturedAt);
    }

    public boolean sameWorkspaceState(WorkspaceBaseline other) {
        return other != null
                && repositoryRoot.equals(other.repositoryRoot)
                && branch.equals(other.branch)
                && head.equals(other.head)
                && clean == other.clean
                && diffHash.equals(other.diffHash);
    }

    public boolean belongsToSameRepository(WorkspaceBaseline other) {
        return other != null && repositoryRoot.equals(other.repositoryRoot)
                && branch.equals(other.branch);
    }
}
