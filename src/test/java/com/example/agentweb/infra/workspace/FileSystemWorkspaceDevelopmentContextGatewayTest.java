package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.domain.workbench.RepositoryBuildTool;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentContext;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentContextClassifier;
import com.example.agentweb.domain.workbench.RepositoryTechnologyType;
import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository Scope 根级 marker 安全探测测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class FileSystemWorkspaceDevelopmentContextGatewayTest {

    @TempDir
    Path tempDir;

    @Test
    void inspectShouldReturnOnlySafeRelativeDevelopmentFactsForSelectedRepositories()
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path backend = repository(workspace, "backend");
        Path frontend = repository(workspace, "frontend");
        Path unselected = repository(workspace, "unselected-sibling");
        write(backend, "pom.xml", "<project/>");
        write(backend, "build.gradle.kts", "plugins {}");
        write(backend, "README.md", "# Backend");
        write(backend, "AGENTS.md", "must never be discovered");
        write(frontend, "package.json", "{}");
        write(frontend, "pyproject.toml", "[project]");
        write(frontend, "CONTRIBUTING.md", "# Contributing");
        write(frontend, "CLAUDE.md", "must never be discovered");
        write(unselected, "Cargo.toml", "[package]");
        write(unselected, "DEVELOPMENT.md", "must remain outside scope");
        RepositoryScope scope = scope(workspace, "backend", "frontend");

        WorkspaceDevelopmentContext context =
                new FileSystemWorkspaceDevelopmentContextGateway().inspect(scope);

        RepositoryDevelopmentContext backendContext = context.primaryRepository();
        assertEquals(Arrays.asList(RepositoryTechnologyType.JAVA),
                backendContext.getTechnologyTypes());
        assertEquals(Arrays.asList(RepositoryBuildTool.MAVEN, RepositoryBuildTool.GRADLE),
                backendContext.getBuildTools());
        assertEquals(Collections.singletonList("README.md"),
                instructionPaths(backendContext));
        RepositoryDevelopmentContext frontendContext = context.requireRepository("frontend");
        assertEquals(Arrays.asList(
                        RepositoryTechnologyType.NODE_JS,
                        RepositoryTechnologyType.PYTHON),
                frontendContext.getTechnologyTypes());
        assertEquals(Collections.singletonList("CONTRIBUTING.md"),
                instructionPaths(frontendContext));
        assertEquals(scope.getScopeHash(), context.getRepositoryScopeHash());
        assertEquals(Arrays.asList("backend", "frontend"), context.repositoryKeys());
        assertSafe(context, tempDir);
    }

    @Test
    void inspectShouldIgnoreSymlinkOversizedNestedUnknownAndCliInstructionFiles()
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("bounded-workspace"));
        Path repository = repository(workspace, "agent-web");
        Path outside = tempDir.resolve("outside.md");
        Files.write(outside, "outside secret".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(repository.resolve("README.md"), outside);
        write(repository, "package.json", "0123456789abcdefg");
        write(repository, "requirements.txt", "safe");
        write(repository, "AGENTS.md", "ignored");
        write(repository, "CLAUDE.md", "ignored");
        Path nested = Files.createDirectories(repository.resolve("nested"));
        Files.write(nested.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
        RepositoryScope scope = scope(workspace, "agent-web");
        FileSystemWorkspaceDevelopmentContextGateway gateway =
                new FileSystemWorkspaceDevelopmentContextGateway(
                        new RepositoryDevelopmentContextClassifier(), 16L);

        RepositoryDevelopmentContext context = gateway.inspect(scope).primaryRepository();

        assertEquals(Collections.singletonList(RepositoryTechnologyType.PYTHON),
                context.getTechnologyTypes());
        assertEquals(Collections.singletonList(RepositoryBuildTool.PIP_REQUIREMENTS),
                context.getBuildTools());
        assertTrue(context.getInstructionReferences().isEmpty());
        assertEquals(Collections.singletonList("requirements.txt"),
                context.getDetectedMarkerPaths());
    }

    @Test
    void inspectShouldFailClosedWithSafeMessageWhenRepositoryIdentityChanges()
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("identity-workspace"));
        Path repository = repository(workspace, "agent-web");
        RepositoryScope scope = scope(workspace, "agent-web");
        Path moved = tempDir.resolve("moved-agent-web");
        Files.move(repository, moved);
        Files.createSymbolicLink(repository, moved);

        WorkspaceOperationException failure = assertThrows(WorkspaceOperationException.class,
                () -> new FileSystemWorkspaceDevelopmentContextGateway().inspect(scope));

        assertEquals(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION, failure.getCode());
        assertFalse(failure.getMessage().contains(tempDir.toString()));
        assertFalse(failure.getMessage().contains(repository.toString()));
    }

    private Path repository(Path workspace, String repositoryKey) throws Exception {
        Path repository = Files.createDirectories(workspace.resolve(repositoryKey));
        Files.createDirectories(repository.resolve(".git/objects"));
        return repository;
    }

    private RepositoryScope scope(Path workspace, String primary, String... others)
            throws Exception {
        List<String> keys = new ArrayList<String>();
        keys.add(primary);
        keys.addAll(Arrays.asList(others));
        List<ResolvedRepository> resolved = new ArrayList<ResolvedRepository>();
        for (String key : keys) {
            Path realRoot = workspace.resolve(key).toRealPath();
            resolved.add(ResolvedRepository.fromVerifiedFacts(
                    key, realRoot.toString(),
                    WorkspaceFileSystemSecurity.rootFingerprint(realRoot), false));
        }
        return RepositoryScope.create(workspace.toRealPath().toString(),
                RepositorySelection.of(primary, keys), resolved, 50);
    }

    private void write(Path repository, String relativePath, String content) throws Exception {
        Files.write(repository.resolve(relativePath), content.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> instructionPaths(RepositoryDevelopmentContext context) {
        return context.getInstructionReferences().stream()
                .map(reference -> reference.getRelativePath())
                .collect(Collectors.toList());
    }

    private void assertSafe(WorkspaceDevelopmentContext context, Path forbiddenRoot) {
        String serializedFacts = context.getRepositories().stream()
                .map(repository -> repository.getRepositoryKey()
                        + repository.getDetectedMarkerPaths()
                        + repository.getInstructionReferences().stream()
                        .map(reference -> reference.getRelativePath())
                        .collect(Collectors.toList()))
                .collect(Collectors.joining("|"));
        assertFalse(serializedFacts.contains(forbiddenRoot.toString()));
        assertFalse(serializedFacts.contains("AGENTS.md"));
        assertFalse(serializedFacts.contains("CLAUDE.md"));
        assertFalse(serializedFacts.contains("must never be discovered"));
        assertFalse(serializedFacts.contains("unselected-sibling"));
    }
}
