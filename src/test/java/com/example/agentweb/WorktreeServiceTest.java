package com.example.agentweb;

import com.example.agentweb.app.WorktreeService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WorktreeService.
 * Uses real git repos in a temp directory to verify worktree operations.
 */
class WorktreeServiceTest {

    @TempDir
    Path tempDir;

    private WorktreeService service;
    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorktreeService();
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
    }

    /**
     * Helper: init a bare git repo with a commit, then create a branch.
     */
    private Path createRepo(String name, String... branches) throws Exception {
        Path repoDir = workspace.resolve(name);
        Files.createDirectories(repoDir);
        git(repoDir, "init");
        git(repoDir, "config", "user.email", "test@test.com");
        git(repoDir, "config", "user.name", "test");
        // initial commit
        Files.write(repoDir.resolve("README.md"), ("# " + name).getBytes());
        git(repoDir, "add", ".");
        git(repoDir, "commit", "-m", "init");
        // create branches
        for (String branch : branches) {
            git(repoDir, "branch", branch);
        }
        return repoDir;
    }

    private void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // consume output
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) {}
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("git command failed: " + String.join(" ", cmd));
        }
    }

    // ========== RED phase tests ==========

    @Test
    @DisplayName("switchBranch: 为所有拥有该分支的仓库创建 worktree")
    void switchBranch_createsWorktreeForReposWithBranch() throws Exception {
        createRepo("service-a", "feature/login");
        createRepo("service-b", "feature/login");
        createRepo("service-c"); // no feature/login branch

        Map<String, Object> result = service.switchBranch(workspace.toString(), "feature/login");

        // Should return worktree base path
        String worktreePath = (String) result.get("worktreePath");
        assertNotNull(worktreePath);
        assertTrue(Files.isDirectory(Paths.get(worktreePath)));

        // Should report per-repo results
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repos = (List<Map<String, Object>>) result.get("repos");
        assertEquals(3, repos.size());

        // service-a and service-b should have worktrees created
        Map<String, Object> repoA = repos.stream().filter(r -> "service-a".equals(r.get("name"))).findFirst().orElseThrow(() -> new RuntimeException("not found"));
        assertTrue((Boolean) repoA.get("created"));
        assertTrue(Files.isDirectory(Paths.get(worktreePath, "service-a")));

        Map<String, Object> repoB = repos.stream().filter(r -> "service-b".equals(r.get("name"))).findFirst().orElseThrow(() -> new RuntimeException("not found"));
        assertTrue((Boolean) repoB.get("created"));

        // service-c should NOT have worktree
        Map<String, Object> repoC = repos.stream().filter(r -> "service-c".equals(r.get("name"))).findFirst().orElseThrow(() -> new RuntimeException("not found"));
        assertFalse((Boolean) repoC.get("created"));
    }

    @Test
    @DisplayName("switchBranch: 已存在的 worktree 直接复用，不重复创建")
    void switchBranch_reusesExistingWorktree() throws Exception {
        createRepo("service-a", "feature/login");

        // First call
        Map<String, Object> result1 = service.switchBranch(workspace.toString(), "feature/login");
        String path1 = (String) result1.get("worktreePath");

        // Second call - should reuse
        Map<String, Object> result2 = service.switchBranch(workspace.toString(), "feature/login");
        String path2 = (String) result2.get("worktreePath");

        assertEquals(path1, path2);
        assertTrue(Files.isDirectory(Paths.get(path2, "service-a")));
    }

    @Test
    @DisplayName("switchBranch: 分支名中的斜杠被安全转换为目录名")
    void switchBranch_sanitizesBranchName() throws Exception {
        createRepo("service-a", "feature/deep/nested");

        Map<String, Object> result = service.switchBranch(workspace.toString(), "feature/deep/nested");

        String worktreePath = (String) result.get("worktreePath");
        // Path should not contain raw slashes from branch name
        String dirName = Paths.get(worktreePath).getFileName().toString();
        assertFalse(dirName.contains("/"));
    }

    @Test
    @DisplayName("switchBranch: 工作空间路径不存在时抛出异常")
    void switchBranch_throwsForInvalidWorkspace() {
        assertThrows(IllegalArgumentException.class,
                () -> service.switchBranch("/nonexistent/path", "master"));
    }

    @Test
    @DisplayName("switchBranch: 工作空间下没有 git 仓库时返回空 repos 列表")
    void switchBranch_emptyWorkspace() throws Exception {
        // workspace exists but has no git repos
        Files.createDirectories(workspace.resolve("not-a-repo"));

        Map<String, Object> result = service.switchBranch(workspace.toString(), "some-branch");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repos = (List<Map<String, Object>>) result.get("repos");
        assertTrue(repos.isEmpty());
    }

    @Test
    @DisplayName("listWorktrees: 列出工作空间下所有已创建的 worktree 分支")
    void listWorktrees_returnsCreatedBranches() throws Exception {
        createRepo("service-a", "branch-1", "branch-2");

        service.switchBranch(workspace.toString(), "branch-1");
        service.switchBranch(workspace.toString(), "branch-2");

        List<Map<String, Object>> list = service.listWorktrees(workspace.toString());

        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(m -> "branch-1".equals(m.get("branch"))));
        assertTrue(list.stream().anyMatch(m -> "branch-2".equals(m.get("branch"))));
    }

    @Test
    @DisplayName("listWorktrees: 无 worktree 时返回空列表")
    void listWorktrees_emptyWhenNone() throws Exception {
        List<Map<String, Object>> list = service.listWorktrees(workspace.toString());
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("removeWorktree: 删除指定分支的所有 worktree")
    void removeWorktree_removesWorktreeAndDirectory() throws Exception {
        createRepo("service-a", "to-remove");
        service.switchBranch(workspace.toString(), "to-remove");

        // Verify worktree exists
        String worktreePath = workspace.resolve(".worktrees").resolve("to-remove").toString();
        assertTrue(Files.isDirectory(Paths.get(worktreePath)));

        // Remove
        service.removeWorktree(workspace.toString(), "to-remove");

        // Verify removed
        assertFalse(Files.exists(Paths.get(worktreePath)));

        // List should be empty
        List<Map<String, Object>> list = service.listWorktrees(workspace.toString());
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("removeWorktree: 删除不存在的 worktree 不报错")
    void removeWorktree_noErrorWhenNotExists() {
        assertDoesNotThrow(() -> service.removeWorktree(workspace.toString(), "nonexistent"));
    }

    @Test
    @DisplayName("switchBranch: worktree 中的文件内容对应分支代码")
    void switchBranch_worktreeContainsBranchCode() throws Exception {
        Path repo = createRepo("service-a", "feature/new-file");

        // Add a file on the feature branch
        git(repo, "checkout", "feature/new-file");
        Files.write(repo.resolve("feature.txt"), "feature content".getBytes());
        git(repo, "add", ".");
        git(repo, "commit", "-m", "add feature file");
        git(repo, "checkout", "master");

        // Switch to the feature branch via worktree
        Map<String, Object> result = service.switchBranch(workspace.toString(), "feature/new-file");
        String worktreePath = (String) result.get("worktreePath");

        // Verify feature.txt exists in worktree but not in original
        Path worktreeFile = Paths.get(worktreePath, "service-a", "feature.txt");
        assertTrue(Files.exists(worktreeFile));
        assertEquals("feature content", new String(Files.readAllBytes(worktreeFile)));

        // Original should NOT have the file (on master)
        assertFalse(Files.exists(repo.resolve("feature.txt")));
    }
}
