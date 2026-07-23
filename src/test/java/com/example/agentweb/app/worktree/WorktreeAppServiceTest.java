package com.example.agentweb.app.worktree;

import com.example.agentweb.infra.RealPathWorkspacePolicy;
import com.example.agentweb.infra.git.LocalWorktreeFileGateway;
import com.example.agentweb.infra.git.ProcessGitWorktreeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for WorktreeAppService.
 * Uses real git repos in a temp directory to verify worktree operations.
 */
@Tag("git-integration")
class WorktreeAppServiceTest {

    @TempDir
    Path tempDir;

    private WorktreeAppService service;
    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        service = worktreeServiceAllowedUnder(tempDir);
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

    @Test
    @DisplayName("switchBranch: 创建真实 worktree 与 fallback 链接并复用已有目录")
    void switchBranch_createsWorktreeForReposWithBranch() throws Exception {
        Path repo = createRepo("service-a", "feature/login");
        createRepo("service-b");
        git(repo, "checkout", "feature/login");
        Files.write(repo.resolve("feature.txt"), "feature content".getBytes());
        git(repo, "add", ".");
        git(repo, "commit", "-m", "add feature file");
        git(repo, "checkout", "master");

        WorktreeSwitchView result = service.switchBranch(null, workspace.toString(),"feature/login");

        String worktreePath = result.worktreePath();
        assertNotNull(worktreePath);
        assertTrue(Files.isDirectory(Paths.get(worktreePath)));

        List<WorktreeRepoSwitchView> repos = result.repos();
        assertEquals(2, repos.size());

        WorktreeRepoSwitchView repoA = repos.stream().filter(r -> "service-a".equals(r.name())).findFirst().orElseThrow(() -> new RuntimeException("not found"));
        assertTrue(repoA.created());
        assertTrue(Files.isDirectory(Paths.get(worktreePath, "service-a")));

        WorktreeRepoSwitchView repoB = repos.stream().filter(r -> "service-b".equals(r.name())).findFirst().orElseThrow(() -> new RuntimeException("not found"));
        assertTrue(repoB.created());
        assertEquals("master", repoB.actualBranch());
        assertTrue(isDirectoryLink(Paths.get(worktreePath, "service-b")),
                "service-b should be a directory link (symlink on *nix, junction on Windows)");

        Path worktreeFile = Paths.get(worktreePath, "service-a", "feature.txt");
        assertTrue(Files.exists(worktreeFile));
        assertEquals("feature content", new String(Files.readAllBytes(worktreeFile)));
        assertFalse(Files.exists(repo.resolve("feature.txt")));

        WorktreeSwitchView second = service.switchBranch(null, workspace.toString(),"feature/login");
        assertEquals(worktreePath, second.worktreePath());
        assertTrue(Files.isDirectory(Paths.get(worktreePath, "service-a")));
    }

    /**
     * 跨平台 "目录链接" 检测：Unix 符号链接或 Windows NTFS junction 皆视为链接。
     * junction 不会被 {@link Files#isSymbolicLink} 识别，需通过 {@code toRealPath} 比较判断。
     */
    private static boolean isDirectoryLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows && Files.isDirectory(path)) {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent == null) {
                    return false;
                }
                // 父目录规范化后拼回叶子名 = path 若为普通目录时应有的真实路径；
                // 直接用 path.toAbsolutePath().normalize() 比较会因 8.3 短名未展开而误判。
                Path expectedReal = parent.toRealPath().resolve(path.getFileName());
                return !path.toRealPath().equals(expectedReal);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    @Test
    @DisplayName("switchBranch: 分支名中的斜杠被安全转换为目录名")
    void switchBranch_sanitizesBranchName() throws Exception {
        WorktreeSwitchView result = service.switchBranch(null, workspace.toString(),"feature/deep/nested");

        String dirName = Paths.get(result.worktreePath()).getFileName().toString();

        assertFalse(dirName.contains("/"));
    }

    @Test
    @DisplayName("switchBranch: 工作空间路径不存在时抛出异常")
    void switchBranch_throwsForInvalidWorkspace() {
        assertThrows(IllegalArgumentException.class,
                () -> service.switchBranch(null, "/nonexistent/path", "master"));
    }

    @Test
    @DisplayName("switchBranch: 工作空间下没有 git 仓库时返回空 repos 列表")
    void switchBranch_emptyWorkspace() throws Exception {
        // workspace exists but has no git repos
        Files.createDirectories(workspace.resolve("not-a-repo"));

        WorktreeSwitchView result = service.switchBranch(null, workspace.toString(),"some-branch");

        assertTrue(result.repos().isEmpty());
    }

    @Test
    @DisplayName("switchBranch: 同步 workspace 根 AGENTS.md 到 worktree 根")
    void switchBranch_syncsAgentsMdToWorktreeRoot() throws Exception {
        Path source = workspace.resolve("AGENTS.md");
        Files.write(source, "first rules".getBytes(StandardCharsets.UTF_8));

        WorktreeSwitchView result = service.switchBranch("Q600041", workspace.toString(), "feature/login");
        Path copied = Paths.get(result.worktreePath()).resolve("AGENTS.md");
        assertTrue(Files.isRegularFile(copied));
        assertEquals("first rules", readUtf8(copied));

        Files.write(source, "second rules".getBytes(StandardCharsets.UTF_8));
        WorktreeSwitchView second = service.switchBranch("Q600041", workspace.toString(), "feature/login");

        assertEquals(result.worktreePath(), second.worktreePath());
        assertEquals("second rules", readUtf8(copied));
    }

    @Test
    @DisplayName("listWorktrees: 列出工作空间下所有已创建的 worktree 分支")
    void listWorktrees_returnsCreatedBranches() throws Exception {
        Files.createDirectories(workspace.resolve(".worktrees/u-_local/branch-1/service-a/.git"));
        Files.createDirectories(workspace.resolve(".worktrees/u-_local/branch-2/service-a/.git"));

        List<WorktreeBranchView> list = service.listWorktrees(null, workspace.toString());

        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(m -> "branch-1".equals(m.branch())));
        assertTrue(list.stream().anyMatch(m -> "branch-2".equals(m.branch())));
    }

    @Test
    @DisplayName("listWorktrees: 无 worktree 时返回空列表")
    void listWorktrees_emptyWhenNone() throws Exception {
        List<WorktreeBranchView> list = service.listWorktrees(null, workspace.toString());
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("removeWorktree: 删除指定分支的所有 worktree")
    void removeWorktree_removesWorktreeAndDirectory() throws Exception {
        Path worktreeBase = workspace.resolve(".worktrees/u-_local/to-remove");
        Files.createDirectories(worktreeBase);
        assertTrue(Files.isDirectory(worktreeBase));

        service.removeWorktree(null, workspace.toString(),"to-remove");

        assertFalse(Files.exists(worktreeBase));

        List<WorktreeBranchView> list = service.listWorktrees(null, workspace.toString());
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("removeWorktree: 删除不存在的 worktree 不报错")
    void removeWorktree_noErrorWhenNotExists() {
        assertDoesNotThrow(() -> service.removeWorktree(null, workspace.toString(),"nonexistent"));
    }

    @Test
    @DisplayName("collectGitRepos: 支持嵌套工作空间布局（仓库在第三层）")
    void collectGitRepos_supportsNestedLayout() throws Exception {
        Files.createDirectories(workspace.resolve("team-x/project-a/service-1/.git"));
        Files.createDirectories(workspace.resolve("team-x/project-a/service-2/.git"));
        Files.createDirectories(workspace.resolve("team-y/project-b/service-1/.git"));

        List<GitRepoEntry> repos = new LocalWorktreeFileGateway().collectGitRepos(workspace);
        List<String> repoNames = repos.stream().map(r -> r.relativePath().toString()).toList();

        assertEquals(3, repoNames.size(), "should find all 3 nested repos");
        assertTrue(repoNames.contains("team-x/project-a/service-1")
                || repoNames.contains("team-x\\project-a\\service-1"));
    }

    private String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    // ========== 复用 fs.roots 工作空间白名单 ==========

    @Test
    @DisplayName("workspace 不在 fs.roots 白名单下 → 拒绝(IllegalArgumentException)")
    void switchBranch_workspaceOutsideAllowedRoots_throws() {
        WorktreeAppService restricted = worktreeServiceAllowedUnder(tempDir.resolve("other-root"));

        assertThrows(IllegalArgumentException.class,
                () -> restricted.switchBranch(null, workspace.toString(), "feature/login"));
    }

    @Test
    @DisplayName("workspace 在 fs.roots 白名单下 → 放行")
    void switchBranch_workspaceUnderAllowedRoot_ok() throws Exception {
        WorktreeAppService restricted = worktreeServiceAllowedUnder(tempDir);

        WorktreeSwitchView result = restricted.switchBranch(null, workspace.toString(), "feature/login");
        assertNotNull(result.worktreePath());
        assertTrue(result.repos().isEmpty());
    }

    private WorktreeAppService worktreeServiceAllowedUnder(Path root) {
        com.example.agentweb.app.setting.WorkspaceSettingsQueryService settings =
                () -> com.example.agentweb.domain.setting.WorkspaceSettings.create(root.toString(),
                        java.util.Collections.singletonList(root.toString()),
                        java.util.Collections.<String>emptyList());
        return new WorktreeAppService(new RealPathWorkspacePolicy(settings),
                new ProcessGitWorktreeGateway(), new LocalWorktreeFileGateway());
    }

}
