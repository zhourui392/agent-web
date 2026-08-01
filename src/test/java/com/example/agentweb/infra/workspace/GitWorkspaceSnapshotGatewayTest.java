package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceAnomalyEvidence;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多仓库 Snapshot 采集的真实 Git 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@Tag("git-integration")
class GitWorkspaceSnapshotGatewayTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void captureShouldIncludeCleanAndDirtyRepositoriesWithDeterministicFileEvidence()
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("snapshot-workspace"));
        Path primary = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        GitWorkspaceTestSupport.repository(workspace, "service-b");
        RepositoryScope scope = scope(workspace, "agent-web", "service-b");
        GitWorkspaceSnapshotGateway gateway = gateway(processRunner());

        WorkspaceSnapshot clean = gateway.capture(
                "snapshot-clean", scope, SnapshotPurpose.of("WORKBENCH_CREATE"));
        Files.write(primary.resolve("README.md"), "changed".getBytes(StandardCharsets.UTF_8));
        Files.write(primary.resolve("untracked.txt"), "new".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(primary.resolve("data"));
        Files.write(primary.resolve("data/secrets.properties"),
                "not-a-real-secret".getBytes(StandardCharsets.UTF_8));
        WorkspaceSnapshot dirty = gateway.capture(
                "snapshot-dirty", scope, SnapshotPurpose.of("WORKBENCH_RUN_END"));

        assertTrue(clean.isClean());
        assertFalse(dirty.isClean());
        assertEquals(2, dirty.getRepositories().size());
        assertFalse(dirty.requireRepository("agent-web").isClean());
        assertTrue(dirty.requireRepository("service-b").isClean());
        assertEquals(3, dirty.requireRepository("agent-web").getFiles().size());
        assertTrue(dirty.requireRepository("agent-web").getFiles().stream()
                .anyMatch(file -> file.getPath().equals("data/secrets.properties")
                        && file.isSensitive()));
        assertNotEquals(clean.getStateHash(), dirty.getStateHash());
        assertEquals(NOW, dirty.getCaptureStartedAt());
        assertEquals(NOW, dirty.getCapturedAt());
    }

    @Test
    void captureShouldSupportRepositoryBackedByWorktreeGitFile() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("worktree-snapshot"));
        Path main = GitWorkspaceTestSupport.repository(workspace, "main-repository");
        GitWorkspaceTestSupport.git(main, "branch", "linked-branch");
        Path linked = workspace.resolve("linked-worktree");
        GitWorkspaceTestSupport.git(main, "worktree", "add", linked.toString(), "linked-branch");
        RepositoryScope scope = scope(workspace, "linked-worktree");

        WorkspaceSnapshot snapshot = gateway(processRunner()).capture(
                "snapshot-worktree", scope, SnapshotPurpose.of("WORKBENCH_RUN_START"));

        assertEquals("linked-branch",
                snapshot.requireRepository("linked-worktree").getBranch());
        assertTrue(snapshot.requireRepository("linked-worktree").isClean());
    }

    @Test
    void captureShouldRetryWholeWorkspaceOnceAndRecordAnomalyWhenHeadChanges()
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("retry-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        RepositoryScope scope = scope(workspace, "agent-web");
        HeadMutatingRunner runner = new HeadMutatingRunner(processRunner(), repository, 1);

        WorkspaceSnapshot snapshot = gateway(runner).capture(
                "snapshot-retried", scope, SnapshotPurpose.of("WORKBENCH_RUN_START"));

        assertEquals(1, runner.getMutationCount());
        assertEquals(GitWorkspaceTestSupport.git(repository, "rev-parse", "HEAD").trim(),
                snapshot.requireRepository("agent-web").getHead());
        assertFalse(snapshot.isClean());
        assertEquals(1, snapshot.getAnomalies().size());
        assertEquals(WorkspaceAnomalyEvidence.Kind.SECONDARY_VERIFY_MISMATCH,
                snapshot.getAnomalies().get(0).getKind());
    }

    @Test
    void captureShouldFailClosedWhenHeadChangesAgainDuringRetry() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("unstable-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        RepositoryScope scope = scope(workspace, "agent-web");
        HeadMutatingRunner runner = new HeadMutatingRunner(processRunner(), repository, 2);

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> gateway(runner).capture("snapshot-unstable", scope,
                        SnapshotPurpose.of("WORKBENCH_RUN_START")));

        assertEquals(WorkspaceFailureCode.WORKSPACE_CAPTURE_UNSTABLE, failure.getCode());
        assertEquals(2, runner.getMutationCount());
    }

    @Test
    void captureShouldFailClosedWhenGitOutputExceedsConfiguredLimit() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("bounded-output-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Files.write(repository.resolve("README.md"),
                Collections.nCopies(1000, "changed-line"), StandardCharsets.UTF_8);
        RepositoryScope scope = scope(workspace, "agent-web");
        WorkspaceGitCommandRunner bounded = new ProcessWorkspaceGitCommandRunner(
                Duration.ofSeconds(5), 128);

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> gateway(bounded).capture("snapshot-output-limit", scope,
                        SnapshotPurpose.of("WORKBENCH_RUN_END")));

        assertEquals(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE, failure.getCode());
    }

    private RepositoryScope scope(Path workspace, String primary, String... others) {
        java.util.List<String> repositories = new java.util.ArrayList<String>();
        repositories.add(primary);
        repositories.addAll(Arrays.asList(others));
        GitWorkspaceInspector inspector = new GitWorkspaceInspector(
                GitWorkspaceTestSupport.allowedUnder(tempDir), 3, 50,
                Duration.ofSeconds(10), processRunner());
        return inspector.resolve(workspace.toString(),
                RepositorySelection.of(primary, repositories));
    }

    private GitWorkspaceSnapshotGateway gateway(WorkspaceGitCommandRunner runner) {
        return new GitWorkspaceSnapshotGateway(Clock.fixed(NOW, ZoneOffset.UTC), runner, 10_000);
    }

    private WorkspaceGitCommandRunner processRunner() {
        return new ProcessWorkspaceGitCommandRunner(Duration.ofSeconds(5), 8 * 1024 * 1024);
    }

    private static final class HeadMutatingRunner implements WorkspaceGitCommandRunner {
        private final WorkspaceGitCommandRunner delegate;
        private final Path repository;
        private final int maximumMutations;
        private int headReadCount;
        private int mutationCount;

        private HeadMutatingRunner(WorkspaceGitCommandRunner delegate, Path repository,
                                   int maximumMutations) {
            this.delegate = delegate;
            this.repository = repository;
            this.maximumMutations = maximumMutations;
        }

        @Override
        public WorkspaceGitCommandResult execute(Path directory, String... command) {
            if (isHeadRead(command)) {
                headReadCount++;
                if (headReadCount % 2 == 0 && mutationCount < maximumMutations) {
                    mutateRepository();
                }
            }
            return delegate.execute(directory, command);
        }

        int getMutationCount() {
            return mutationCount;
        }

        private boolean isHeadRead(String[] command) {
            return Arrays.equals(command,
                    new String[]{"git", "rev-parse", "--verify", "HEAD"});
        }

        private void mutateRepository() {
            try {
                mutationCount++;
                Files.write(repository.resolve("mutation.txt"),
                        ("mutation-" + mutationCount).getBytes(StandardCharsets.UTF_8));
                GitWorkspaceTestSupport.git(repository, "add", "mutation.txt");
                GitWorkspaceTestSupport.git(repository, "commit", "-m",
                        "mutation " + mutationCount);
            } catch (Exception ex) {
                throw new IllegalStateException("could not mutate Git test repository", ex);
            }
        }
    }
}
