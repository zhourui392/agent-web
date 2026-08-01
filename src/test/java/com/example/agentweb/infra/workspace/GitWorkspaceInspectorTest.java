package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceInspection;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.WorkspaceRepositoryCandidate;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace Inspect 与 Scope 解析的真实 Git/文件系统测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@Tag("git-integration")
class GitWorkspaceInspectorTest {

    @TempDir
    Path tempDir;

    @Test
    void inspectShouldDiscoverSiblingRepositoriesWithoutTreatingParentAsRepository() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        GitWorkspaceTestSupport.repository(workspace, "agent-web");
        GitWorkspaceTestSupport.repository(workspace, "service-b");
        Files.createDirectories(workspace.resolve("notes"));

        WorkspaceInspection inspection = inspector().inspect(workspace.toString());

        assertEquals("DISCOVERY", inspection.getSource().name());
        assertEquals(workspace.toRealPath().toString(), inspection.getWorkspaceRootDisplay());
        assertFalse(inspection.getInspectionToken().isEmpty());
        assertEquals(Arrays.asList("agent-web", "service-b"), Arrays.asList(
                inspection.getRepositories().get(0).getRepositoryKey(),
                inspection.getRepositories().get(1).getRepositoryKey()));
        assertTrue(inspection.getRepositories().get(0).isPrimarySuggested());
        assertTrue(inspection.getRepositories().stream()
                .allMatch(WorkspaceRepositoryCandidate::isSelectedByDefault));
    }

    @Test
    void inspectShouldUseManifestBeforeDiscoveryAndHonorPrimarySuggestion() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("manifest-workspace"));
        GitWorkspaceTestSupport.repository(workspace, "agent-web");
        GitWorkspaceTestSupport.repository(workspace, "service-b");
        GitWorkspaceTestSupport.repository(workspace, "ignored-repository");
        Files.write(workspace.resolve(".agent-web.yml"), Arrays.asList(
                "workbench:",
                "  repositories:",
                "    - service-b",
                "    - agent-web",
                "  primary_repository: service-b"), StandardCharsets.UTF_8);

        WorkspaceInspection inspection = inspector().inspect(workspace.toString());

        assertEquals("MANIFEST", inspection.getSource().name());
        assertEquals(2, inspection.getRepositories().size());
        assertEquals("agent-web", inspection.getRepositories().get(0).getRepositoryKey());
        assertEquals("service-b", inspection.getRepositories().get(1).getRepositoryKey());
        assertFalse(inspection.getRepositories().get(0).isPrimarySuggested());
        assertTrue(inspection.getRepositories().get(1).isPrimarySuggested());
    }

    @Test
    void inspectShouldRecognizeLinkedWorktreeGitFile() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("worktree-workspace"));
        Path main = GitWorkspaceTestSupport.repository(workspace, "main-repository");
        GitWorkspaceTestSupport.git(main, "branch", "linked-branch");
        Path linked = workspace.resolve("linked-worktree");
        GitWorkspaceTestSupport.git(main, "worktree", "add", linked.toString(), "linked-branch");

        WorkspaceInspection inspection = inspector().inspect(workspace.toString());

        assertTrue(Files.isRegularFile(linked.resolve(".git")));
        assertTrue(inspection.getRepositories().stream()
                .anyMatch(candidate -> candidate.getRepositoryKey().equals("linked-worktree")
                        && candidate.getBranch().equals("linked-branch")));
    }

    @Test
    void resolveShouldRevalidateExplicitSelectionAndCreateImmutableScope() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("scope-workspace"));
        Path primary = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Path additional = GitWorkspaceTestSupport.repository(workspace, "service-b");

        RepositoryScope scope = inspector().resolve(workspace.toString(),
                RepositorySelection.of("agent-web", Arrays.asList("service-b", "agent-web")));

        assertEquals(workspace.toRealPath().toString(), scope.getWorkspaceRoot());
        assertEquals(primary.toRealPath().toString(),
                scope.primaryRepository().getRepositoryRoot());
        assertEquals(additional.toRealPath().toString(),
                scope.requireRepository("service-b").getRepositoryRoot());
        assertEquals(2, scope.repositoryCount());
    }

    @Test
    void resolveShouldRejectSymbolicLinkRepositoryEntry() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("symlink-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "real-repository");
        Files.createSymbolicLink(workspace.resolve("repository-alias"), repository);

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> inspector().resolve(workspace.toString(), RepositorySelection.of(
                        "repository-alias", Collections.singletonList("repository-alias"))));

        assertEquals(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN, failure.getCode());
    }

    @Test
    void resolveShouldRejectNestedRepositoryRootsEvenWhenKeysAreNotNested() throws Exception {
        Path workspace = GitWorkspaceTestSupport.repository(tempDir, "nested-workspace");
        GitWorkspaceTestSupport.repository(workspace, "child");
        String rootKey = workspace.getFileName().toString();

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> inspector().resolve(workspace.toString(), RepositorySelection.of(
                        rootKey, Arrays.asList(rootKey, "child"))));

        assertEquals(WorkspaceFailureCode.WORKSPACE_REPOSITORY_OVERLAP, failure.getCode());
    }

    @Test
    void inspectShouldFailClosedWhenRepositoryHasNoHead() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("headless-workspace"));
        Path headless = Files.createDirectories(workspace.resolve("headless"));
        GitWorkspaceTestSupport.git(headless, "init", "-b", "main");

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> inspector().inspect(workspace.toString()));

        assertEquals(WorkspaceFailureCode.WORKSPACE_REPOSITORY_HEAD_MISSING,
                failure.getCode());
    }

    @Test
    void inspectShouldRejectWorkspaceOutsideConfiguredAllowlist() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        GitWorkspaceTestSupport.repository(outside, "repository");
        GitWorkspaceInspector inspector = new GitWorkspaceInspector(
                GitWorkspaceTestSupport.allowedUnder(allowed), 3, 50,
                Duration.ofSeconds(10), new ProcessWorkspaceGitCommandRunner(
                        Duration.ofSeconds(5), 8 * 1024 * 1024));

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> inspector.inspect(outside.toString()));

        assertEquals(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN, failure.getCode());
        assertFalse(failure.getMessage().contains(outside.toString()));
    }

    @Test
    void inspectShouldSkipSymlinkDirectoriesAndIgnoredBuildTrees() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("bounded-workspace"));
        Path outside = GitWorkspaceTestSupport.repository(tempDir, "outside-repository");
        Files.createSymbolicLink(workspace.resolve("linked-repository"), outside);
        GitWorkspaceTestSupport.repository(workspace.resolve("target"), "generated-repository");
        GitWorkspaceTestSupport.repository(workspace, "included-repository");

        WorkspaceInspection inspection = inspector().inspect(workspace.toString());

        assertEquals(1, inspection.getRepositories().size());
        assertEquals("included-repository",
                inspection.getRepositories().get(0).getRepositoryKey());
    }

    private GitWorkspaceInspector inspector() {
        return new GitWorkspaceInspector(GitWorkspaceTestSupport.allowedUnder(tempDir), 3, 50,
                Duration.ofSeconds(10), new ProcessWorkspaceGitCommandRunner(
                        Duration.ofSeconds(5), 8 * 1024 * 1024));
    }
}
