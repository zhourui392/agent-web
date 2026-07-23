package com.example.agentweb.infra.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessGitWorktreeGateway} 进程原语的轻量集成测试(真实子进程, 不起 Spring)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Tag("git-integration")
class ProcessGitWorktreeGatewayTest {

    @TempDir
    Path tempDir;

    private final ProcessGitWorktreeGateway gateway = new ProcessGitWorktreeGateway();

    @Test
    @DisplayName("execWithTimeout: 子进程超过超时时间时返回 -1")
    void execWithTimeout_returnsMinusOne_When_ProcessExceedsTimeout() throws Exception {
        int exitCode = gateway.execWithTimeout(tempDir.toFile(), 1, sleepCommand());

        assertEquals(-1, exitCode);
    }

    @Test
    @DisplayName("addWorktree: 在真实仓库创建私有分支 Worktree")
    void addWorktree_realRepository_shouldCreateRequestedPrivateRef() throws Exception {
        Path repository = createRepository();
        Path worktree = tempDir.resolve("worktree-add");

        com.example.agentweb.app.worktree.GitExecResult result = gateway.addWorktree(
                repository.toFile(), worktree, "wt/alice/feature/test", "HEAD");

        assertTrue(result.isSuccess(), result.output());
        assertEquals("wt/alice/feature/test", gateway.currentBranchRef(worktree));
    }

    @Test
    @DisplayName("pruneWorktrees: 清理物理目录已丢失的陈旧注册")
    void pruneWorktrees_staleRegistration_shouldDisappear() throws Exception {
        Path repository = createRepository();
        Path worktree = tempDir.resolve("worktree-stale");
        String privateRef = "wt/alice/feature/stale";
        assertTrue(gateway.addWorktree(repository.toFile(), worktree, privateRef, "HEAD").isSuccess());
        deleteRecursively(worktree);

        gateway.pruneWorktrees(repository.toFile());

        assertNull(gateway.findCheckoutPath(repository.toFile(), privateRef));
    }

    private Path createRepository() throws Exception {
        Path repository = tempDir.resolve("repository-" + System.nanoTime());
        Files.createDirectories(repository);
        git(repository, "init");
        git(repository, "config", "user.email", "worktree-test@example.com");
        git(repository, "config", "user.name", "worktree-test");
        Files.write(repository.resolve("README.md"), "init".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "init");
        return repository;
    }

    private void git(Path directory, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output.toString());
    }

    private void deleteRecursively(Path root) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private String[] sleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return new String[]{"powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 2"};
        }
        return new String[]{"sh", "-c", "sleep 2"};
    }
}
