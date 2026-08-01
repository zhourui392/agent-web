package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.port.WorkspaceFileReference;
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

/**
 * Repository Scope 约束下的结构化路径解析测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@Tag("git-integration")
class ScopedPathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveExistingShouldReturnRealSelectedRepositoryPath() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("document-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Path document = repository.resolve("docs/design.md");
        Files.createDirectories(document.getParent());
        Files.write(document, "design".getBytes(StandardCharsets.UTF_8));
        RepositoryScope scope = scope(workspace, "agent-web");

        Path resolved = new ScopedPathResolver().resolveExisting(
                scope, "agent-web", "docs/design.md");

        assertEquals(document.toRealPath(), resolved);
    }

    @Test
    void resolveDirectoryShouldAllowExactRepositoryRootAndNestedDirectory()
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("directory-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Path nested = Files.createDirectories(repository.resolve("docs/design"));
        RepositoryScope scope = scope(workspace, "agent-web");
        ScopedPathResolver resolver = new ScopedPathResolver();

        assertEquals(repository.toRealPath(),
                resolver.resolveDirectory(scope, "agent-web", ""));
        assertEquals(nested.toRealPath(),
                resolver.resolveDirectory(scope, "agent-web", "docs/design"));
    }

    @Test
    void resolveDirectoryShouldRejectNullFileAndRootAliases() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("directory-invalid-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Files.write(repository.resolve("docs.txt"),
                "not a directory".getBytes(StandardCharsets.UTF_8));
        RepositoryScope scope = scope(workspace, "agent-web");
        ScopedPathResolver resolver = new ScopedPathResolver();

        for (String relativePath : Arrays.asList(".", "./", "docs/", "/")) {
            assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                    assertThrows(WorkspaceOperationException.class,
                            () -> resolver.resolveDirectory(
                                    scope, "agent-web", relativePath)).getCode());
        }
        assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                assertThrows(WorkspaceOperationException.class,
                        () -> resolver.resolveDirectory(
                                scope, "agent-web", null)).getCode());
        assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                assertThrows(WorkspaceOperationException.class,
                        () -> resolver.resolveDirectory(
                                scope, "agent-web", "docs.txt")).getCode());
    }

    @Test
    void resolveExistingShouldRejectAbsoluteTraversalBackslashAndControlCharacters()
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("invalid-path-workspace"));
        GitWorkspaceTestSupport.repository(workspace, "agent-web");
        RepositoryScope scope = scope(workspace, "agent-web");
        ScopedPathResolver resolver = new ScopedPathResolver();

        for (String relativePath : Arrays.asList(
                "/etc/passwd", "../service-b/README.md", "docs/../README.md",
                "docs\\design.md", "docs/line\nfeed.md")) {
            WorkspaceOperationException failure = assertThrows(
                    WorkspaceOperationException.class,
                    () -> resolver.resolveExisting(scope, "agent-web", relativePath));
            assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION, failure.getCode());
            assertFalse(failure.getMessage().contains(workspace.toString()));
        }
    }

    @Test
    void resolveExistingShouldRejectSymlinkFileAndSymlinkDirectory() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("symlink-path-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Path docs = Files.createDirectories(repository.resolve("docs"));
        Path target = docs.resolve("target.md");
        Files.write(target, "target".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(docs.resolve("linked.md"), target);
        Files.createSymbolicLink(repository.resolve("linked-docs"), docs);
        RepositoryScope scope = scope(workspace, "agent-web");
        ScopedPathResolver resolver = new ScopedPathResolver();

        assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                assertThrows(WorkspaceOperationException.class,
                        () -> resolver.resolveExisting(
                                scope, "agent-web", "docs/linked.md")).getCode());
        assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                assertThrows(WorkspaceOperationException.class,
                        () -> resolver.resolveExisting(
                                scope, "agent-web", "linked-docs/target.md")).getCode());
    }

    @Test
    void resolveExistingShouldRejectUnselectedSiblingRepository() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("selection-workspace"));
        GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Path unselected = GitWorkspaceTestSupport.repository(workspace, "service-b");
        RepositoryScope scope = scope(workspace, "agent-web");
        ScopedPathResolver resolver = new ScopedPathResolver();

        WorkspaceOperationException structuredFailure = assertThrows(
                WorkspaceOperationException.class,
                () -> resolver.resolveExisting(scope, "service-b", "README.md"));
        WorkspaceOperationException absoluteFailure = assertThrows(
                WorkspaceOperationException.class,
                () -> resolver.identifyExisting(scope,
                        unselected.resolve("README.md").toString()));

        assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                structuredFailure.getCode());
        assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                absoluteFailure.getCode());
    }

    @Test
    void resolveExistingShouldFailWhenRepositoryRootWasReplaced() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("replacement-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        RepositoryScope scope = scope(workspace, "agent-web");
        Files.move(repository, workspace.resolve("agent-web-old"));
        GitWorkspaceTestSupport.repository(workspace, "agent-web");

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> new ScopedPathResolver().resolveExisting(
                        scope, "agent-web", "README.md"));

        assertEquals(WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED, failure.getCode());
    }

    @Test
    void identifyExistingShouldMapAbsolutePathToStructuredReference() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("identity-workspace"));
        Path repository = GitWorkspaceTestSupport.repository(workspace, "agent-web");
        Path report = repository.resolve("target/reports/test.txt");
        Files.createDirectories(report.getParent());
        Files.write(report, "passed".getBytes(StandardCharsets.UTF_8));
        RepositoryScope scope = scope(workspace, "agent-web");

        WorkspaceFileReference reference = new ScopedPathResolver().identifyExisting(
                scope, report.toString());

        assertEquals("agent-web", reference.getRepositoryKey());
        assertEquals("target/reports/test.txt", reference.getRelativePath());
    }

    private RepositoryScope scope(Path workspace, String primary, String... others) {
        java.util.List<String> repositories = new java.util.ArrayList<String>();
        repositories.add(primary);
        repositories.addAll(Arrays.asList(others));
        GitWorkspaceInspector inspector = new GitWorkspaceInspector(
                GitWorkspaceTestSupport.allowedUnder(tempDir), 3, 50,
                Duration.ofSeconds(10), new ProcessWorkspaceGitCommandRunner(
                        Duration.ofSeconds(5), 8 * 1024 * 1024));
        return inspector.resolve(workspace.toString(), RepositorySelection.of(
                primary, repositories.isEmpty() ? Collections.singletonList(primary) : repositories));
    }
}
