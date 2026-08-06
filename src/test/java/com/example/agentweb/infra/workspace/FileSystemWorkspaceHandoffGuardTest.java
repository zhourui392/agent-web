package com.example.agentweb.infra.workspace;

import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileSystemWorkspaceHandoffGuard 的轻量集成测试。
 *
 * @author alex
 * @since 2026-08-06
 */
class FileSystemWorkspaceHandoffGuardTest {

    @TempDir
    Path workspace;

    @Test
    void shouldCreateGitignoreWhenAbsent() throws IOException {
        Path repo = newGitRepo("service-a");
        RepositoryScope scope = scope("service-a", repo);

        new FileSystemWorkspaceHandoffGuard().ensureHandoffIgnored(scope);

        Path gitignore = repo.resolve(".gitignore");
        assertTrue(Files.isRegularFile(gitignore));
        String content = Files.readString(gitignore, StandardCharsets.UTF_8);
        assertTrue(content.contains(".workbench/handoff/"));
    }

    @Test
    void shouldAppendEntryToExistingGitignore() throws IOException {
        Path repo = newGitRepo("service-a");
        Files.writeString(repo.resolve(".gitignore"),
                "target/\n*.class\n", StandardCharsets.UTF_8);
        RepositoryScope scope = scope("service-a", repo);

        new FileSystemWorkspaceHandoffGuard().ensureHandoffIgnored(scope);

        String content = Files.readString(
                repo.resolve(".gitignore"), StandardCharsets.UTF_8);
        assertTrue(content.contains("target/"));
        assertTrue(content.contains("*.class"));
        assertTrue(content.contains(".workbench/handoff/"));
    }

    @Test
    void shouldBeIdempotentWhenEntryAlreadyPresent() throws IOException {
        Path repo = newGitRepo("service-a");
        Files.writeString(repo.resolve(".gitignore"),
                "target/\n.workbench/handoff/\n", StandardCharsets.UTF_8);
        RepositoryScope scope = scope("service-a", repo);

        new FileSystemWorkspaceHandoffGuard().ensureHandoffIgnored(scope);

        String content = Files.readString(
                repo.resolve(".gitignore"), StandardCharsets.UTF_8);
        long count = content.lines()
                .filter(line -> line.contains(".workbench/handoff/"))
                .count();
        assertEquals(1, count, "entry should not be duplicated");
    }

    @Test
    void shouldDegradeGracefullyOnIoFailure() throws IOException {
        Path repo = newGitRepo("service-a");
        RepositoryScope scope = scope("service-a", repo);

        // Make .gitignore a directory to trigger IOException on write
        Files.createDirectory(repo.resolve(".gitignore"));

        // Should not throw
        new FileSystemWorkspaceHandoffGuard().ensureHandoffIgnored(scope);

        assertFalse(Files.isRegularFile(repo.resolve(".gitignore")));
    }

    private Path newGitRepo(String key) throws IOException {
        Path repo = workspace.resolve(key);
        Files.createDirectories(repo);
        Files.createDirectories(repo.resolve(".git/objects"));
        return repo;
    }

    private RepositoryScope scope(String key, Path repo) throws IOException {
        ResolvedRepository resolved = ResolvedRepository.fromVerifiedFacts(
                key, repo.toRealPath().toString(),
                WorkspaceFileSystemSecurity.rootFingerprint(repo.toRealPath()),
                false);
        return RepositoryScope.create(
                workspace.toRealPath().toString(),
                RepositorySelection.of(key, Collections.singletonList(key)),
                Collections.singletonList(resolved), 50);
    }
}
