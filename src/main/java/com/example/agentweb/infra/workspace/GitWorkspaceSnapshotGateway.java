package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.port.WorkspaceSnapshotGateway;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workspace.ChangedFileEvidence;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceAnomalyEvidence;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 顺序采集多仓库 Git 状态并通过二次 HEAD/branch 核验保证窗口稳定。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class GitWorkspaceSnapshotGateway implements WorkspaceSnapshotGateway {

    private static final int DEFAULT_MAXIMUM_CHANGED_FILE_BYTES = 8 * 1024 * 1024;
    private static final Duration DEFAULT_CAPTURE_TIMEOUT = Duration.ofSeconds(60);

    private final Clock clock;
    private final WorkspaceGitCommandRunner git;
    private final int maximumChangedFiles;
    private final int maximumChangedFileBytes;
    private final Duration captureTimeout;

    @Autowired
    public GitWorkspaceSnapshotGateway(
            Clock clock,
            @Value("${agent.workbench.workspace.git-command-timeout-seconds:10}")
            long gitCommandTimeoutSeconds,
            @Value("${agent.workbench.workspace.max-git-output-bytes:8388608}")
            int maximumGitOutputBytes,
            @Value("${agent.workbench.workspace.max-changed-files:10000}")
            int maximumChangedFiles,
            @Value("${agent.workbench.workspace.capture-timeout-seconds:60}")
            long captureTimeoutSeconds) {
        this(clock, new ProcessWorkspaceGitCommandRunner(
                        Duration.ofSeconds(gitCommandTimeoutSeconds), maximumGitOutputBytes),
                maximumChangedFiles, maximumGitOutputBytes,
                Duration.ofSeconds(captureTimeoutSeconds));
    }

    GitWorkspaceSnapshotGateway(Clock clock, WorkspaceGitCommandRunner git,
                                int maximumChangedFiles) {
        this(clock, git, maximumChangedFiles, DEFAULT_MAXIMUM_CHANGED_FILE_BYTES,
                DEFAULT_CAPTURE_TIMEOUT);
    }

    private GitWorkspaceSnapshotGateway(Clock clock, WorkspaceGitCommandRunner git,
                                        int maximumChangedFiles,
                                        int maximumChangedFileBytes,
                                        Duration captureTimeout) {
        if (clock == null || git == null) {
            throw new IllegalArgumentException("workspace snapshot clock and Git runner are required");
        }
        if (maximumChangedFiles < 1 || maximumChangedFileBytes < 1) {
            throw new IllegalArgumentException("workspace snapshot limits must be positive");
        }
        if (captureTimeout == null || captureTimeout.isZero() || captureTimeout.isNegative()) {
            throw new IllegalArgumentException("workspace capture timeout must be positive");
        }
        this.clock = clock;
        this.git = git;
        this.maximumChangedFiles = maximumChangedFiles;
        this.maximumChangedFileBytes = maximumChangedFileBytes;
        this.captureTimeout = captureTimeout;
    }

    @Override
    public WorkspaceSnapshot capture(String snapshotId, RepositoryScope scope,
                                     SnapshotPurpose purpose) {
        if (scope == null || purpose == null) {
            throw new IllegalArgumentException("workspace scope and snapshot purpose are required");
        }
        Instant captureStartedAt = clock.instant();
        long deadline = deadline();
        CaptureAttempt attempt = captureAttempt(scope, deadline);
        List<WorkspaceAnomalyEvidence> anomalies =
                Collections.<WorkspaceAnomalyEvidence>emptyList();
        if (!isStable(attempt, deadline)) {
            attempt = captureAttempt(scope, deadline);
            if (!isStable(attempt, deadline)) {
                throw failure(WorkspaceFailureCode.WORKSPACE_CAPTURE_UNSTABLE,
                        "workspace Git identity changed throughout the capture window", null);
            }
            anomalies = Collections.singletonList(
                    WorkspaceAnomalyEvidence.workspaceLevel(
                            WorkspaceAnomalyEvidence.Kind.SECONDARY_VERIFY_MISMATCH,
                            "workspace capture was retried after branch or HEAD changed"));
        }
        WorkspaceTopology topology = topology(scope);
        return WorkspaceSnapshot.capture(snapshotId, purpose, topology, attempt.baselines,
                anomalies, captureStartedAt, clock.instant());
    }

    private CaptureAttempt captureAttempt(RepositoryScope scope, long deadline) {
        List<RepositoryBaseline> baselines = new ArrayList<RepositoryBaseline>();
        List<RepositoryIdentity> identities = new ArrayList<RepositoryIdentity>();
        for (ResolvedRepository repository : scope.getRepositories()) {
            requireBeforeDeadline(deadline);
            Path root = requireCurrentRepository(repository);
            RepositoryIdentity identity = identity(repository.getRepositoryKey(), root);
            identities.add(identity);
            baselines.add(baseline(repository.getRepositoryKey(), root, identity));
        }
        return new CaptureAttempt(baselines, identities);
    }

    private RepositoryBaseline baseline(String repositoryKey, Path root,
                                        RepositoryIdentity identity) {
        WorkspaceGitCommandResult statusResult = required(root,
                "git", "status", "--porcelain=v1", "-z", "--untracked-files=all",
                "--no-renames");
        byte[] status = statusResult.getOutput();
        byte[] diff = required(root, "git", "diff", "--binary", "HEAD", "--")
                .getOutput();
        List<ChangedFileEvidence> files = changedFiles(root, status);
        String diffHash = diffHash(status, diff, files);
        return RepositoryBaseline.capture(repositoryKey, root.toString(), identity.branch,
                identity.head, status.length == 0, diffHash, files, clock.instant());
    }

    private List<ChangedFileEvidence> changedFiles(Path root, byte[] statusOutput) {
        List<String> entries = zeroSeparated(statusOutput);
        if (entries.size() > maximumChangedFiles) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "workspace changed file count exceeds the configured limit", null);
        }
        List<ChangedFileEvidence> files = new ArrayList<ChangedFileEvidence>();
        for (String entry : entries) {
            if (entry.length() < 4 || entry.charAt(2) != ' ') {
                throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                        "Git status returned a malformed entry", null);
            }
            String status = entry.substring(0, 2);
            String relativePath = entry.substring(3);
            Path candidate = relativeCandidate(root, relativePath);
            byte[] state = "??".equals(status)
                    ? untrackedState(root, candidate) : trackedState(root, relativePath);
            String fingerprint = CanonicalHashing.sha256((status + ':'
                    + CanonicalHashing.sha256(state)).getBytes(StandardCharsets.UTF_8));
            files.add(ChangedFileEvidence.observed(relativePath, status, fingerprint));
        }
        files.sort(Comparator.comparing(ChangedFileEvidence::getPath));
        return files;
    }

    private String diffHash(byte[] status, byte[] diff,
                            List<ChangedFileEvidence> files) {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "statusHash",
                CanonicalHashing.sha256(status));
        CanonicalHashing.appendFramed(canonical, "trackedDiffHash",
                CanonicalHashing.sha256(diff));
        for (ChangedFileEvidence file : files) {
            if ("??".equals(file.getStatus())) {
                CanonicalHashing.appendFramed(canonical, "untrackedPath", file.getPath());
                CanonicalHashing.appendFramed(canonical, "untrackedState",
                        file.getStateFingerprint());
            }
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private byte[] trackedState(Path root, String relativePath) {
        return required(root, "git", "diff", "--binary", "HEAD", "--", relativePath)
                .getOutput();
    }

    private byte[] untrackedState(Path root, Path candidate) {
        try {
            if (containsSymbolicLink(root, candidate) || !Files.isRegularFile(
                    candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                        "untracked Git entry is not a regular in-repository file", null);
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root) || Files.size(real) > maximumChangedFileBytes) {
                throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                        "untracked Git entry cannot be captured within configured limits", null);
            }
            return Files.readAllBytes(real);
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "untracked Git entry could not be captured", ex);
        }
    }

    private Path relativeCandidate(Path root, String relativePath) {
        Path relative;
        try {
            relative = Paths.get(relativePath);
        } catch (RuntimeException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "Git status returned an invalid path", ex);
        }
        if (relative.isAbsolute()) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "Git status returned an out-of-scope path", null);
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "Git status returned an out-of-scope path", null);
        }
        return candidate;
    }

    private boolean containsSymbolicLink(Path root, Path candidate) {
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStable(CaptureAttempt attempt, long deadline) {
        for (RepositoryIdentity captured : attempt.identities) {
            requireBeforeDeadline(deadline);
            RepositoryIdentity verified = identity(captured.repositoryKey, captured.root);
            if (!captured.sameBranchAndHead(verified)) {
                return false;
            }
        }
        return true;
    }

    private RepositoryIdentity identity(String repositoryKey, Path root) {
        WorkspaceGitCommandResult branchResult = git.execute(root,
                "git", "symbolic-ref", "--short", "-q", "HEAD");
        String branch = branchResult.getExitCode() == 0
                ? text(branchResult) : "DETACHED";
        if (branch == null) {
            branch = "DETACHED";
        }
        WorkspaceGitCommandResult headResult = git.execute(root,
                "git", "rev-parse", "--verify", "HEAD");
        if (headResult.getExitCode() != 0 || text(headResult) == null) {
            throw failure(WorkspaceFailureCode.WORKSPACE_REPOSITORY_HEAD_MISSING,
                    "selected repository does not have a resolvable HEAD", null);
        }
        return new RepositoryIdentity(repositoryKey, root, branch, text(headResult));
    }

    private Path requireCurrentRepository(ResolvedRepository repository) {
        try {
            Path stored = Paths.get(repository.getRepositoryRoot());
            if (Files.isSymbolicLink(stored)) {
                throw failure(WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED,
                        "repository root identity changed after scope creation", null);
            }
            Path real = stored.toRealPath();
            String fingerprint = WorkspaceFileSystemSecurity.rootFingerprint(real);
            if (!real.equals(stored) || !fingerprint.equals(repository.getRootFingerprint())) {
                throw failure(WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED,
                        "repository root identity changed after scope creation", null);
            }
            return real;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED,
                    "repository root identity is no longer available", ex);
        }
    }

    private WorkspaceGitCommandResult required(Path root, String... command) {
        WorkspaceGitCommandResult result = git.execute(root, command);
        if (result.getExitCode() != 0) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "required Git workspace capture command failed", null);
        }
        return result;
    }

    private List<String> zeroSeparated(byte[] value) {
        List<String> entries = new ArrayList<String>();
        int start = 0;
        for (int index = 0; index < value.length; index++) {
            if (value[index] == 0) {
                if (index > start) {
                    entries.add(new String(
                            value, start, index - start, StandardCharsets.UTF_8));
                }
                start = index + 1;
            }
        }
        if (start < value.length) {
            entries.add(new String(value, start,
                    value.length - start, StandardCharsets.UTF_8));
        }
        return entries;
    }

    private WorkspaceTopology topology(RepositoryScope scope) {
        List<String> keys = new ArrayList<String>();
        for (ResolvedRepository repository : scope.getRepositories()) {
            keys.add(repository.getRepositoryKey());
        }
        return WorkspaceTopology.of(scope.getWorkspaceRoot(), RepositorySelection.of(
                scope.getPrimaryRepositoryKey(), keys));
    }

    private String text(WorkspaceGitCommandResult result) {
        String value = new String(result.getOutput(), StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? null : value;
    }

    private long deadline() {
        long current = System.nanoTime();
        long value = current + captureTimeout.toNanos();
        return value < current ? Long.MAX_VALUE : value;
    }

    private void requireBeforeDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "workspace capture exceeded its configured timeout", null);
        }
    }

    private WorkspaceOperationException failure(WorkspaceFailureCode code, String message,
                                                Throwable cause) {
        return new WorkspaceOperationException(code, message, cause);
    }

    private static final class RepositoryIdentity {
        private final String repositoryKey;
        private final Path root;
        private final String branch;
        private final String head;

        private RepositoryIdentity(String repositoryKey, Path root, String branch, String head) {
            this.repositoryKey = repositoryKey;
            this.root = root;
            this.branch = branch;
            this.head = head;
        }

        private boolean sameBranchAndHead(RepositoryIdentity other) {
            return branch.equals(other.branch) && head.equals(other.head);
        }
    }

    private static final class CaptureAttempt {
        private final List<RepositoryBaseline> baselines;
        private final List<RepositoryIdentity> identities;

        private CaptureAttempt(List<RepositoryBaseline> baselines,
                               List<RepositoryIdentity> identities) {
            this.baselines = baselines;
            this.identities = identities;
        }
    }
}
