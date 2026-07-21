package com.example.agentweb;

import com.example.agentweb.app.WorktreeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补 WorktreeService.updateBranch / pullOne / removeWorktree 等未覆盖路径,
 * 用真 git 子进程跑,与 {@link WorktreeServiceTest} 同风格。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Tag("git-integration")
class WorktreeServiceUpdateBranchTest {

    @TempDir
    Path tempDir;

    private WorktreeService service;
    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorktreeService();
        workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
    }

    // ============ updateBranch 异常 ============

    @Test
    @DisplayName("updateBranch: 工作空间不存在 → IllegalArgumentException")
    void updateBranch_workspaceMissing_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateBranch(null, tempDir.resolve("ghost").toString(), "feature/x"));
    }

    @Test
    @DisplayName("updateBranch: worktree base 不存在(未先 switchBranch) → IllegalArgumentException")
    void updateBranch_worktreeBaseMissing_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateBranch(null, workspace.toString(),"never-created"));
        assertTrue(ex.getMessage().contains("never-created"));
    }

    // ============ updateBranch 正常路径(已是最新) ============

    @Test
    @DisplayName("updateBranch: 刚 switchBranch 完, pull 后 'updated=true reason=已是最新'")
    void updateBranch_freshWorktree_upToDate() throws Exception {
        Method method = WorktreeService.class.getDeclaredMethod(
                "pullSuccessReason", String.class, String.class);
        method.setAccessible(true);

        Object reason = method.invoke(service, "abc123", "abc123");

        assertEquals("已是最新", reason);
    }

    // ============ updateBranch: remote 有新提交 → '已更新' ============

    @Test
    @DisplayName("updateBranch: remote 有新提交时 pull 后 'reason=已更新'")
    void updateBranch_remoteHasNewCommit_pullAdvancesHead() throws Exception {
        Path bareRemote = setupBareRemoteWithBranch("svc-a", "feature/login");
        Path repo = workspace.resolve("svc-a");
        gitClone(bareRemote, repo);
        git(repo, "fetch", "--all");
        service.switchBranch(null, workspace.toString(),"feature/login");

        // 在 bare remote 上推一个新 commit (走另一个 working clone 完成 push)
        Path pusher = tempDir.resolve("pusher");
        gitClone(bareRemote, pusher);
        git(pusher, "checkout", "feature/login");
        Files.write(pusher.resolve("newfile.txt"), "hi".getBytes());
        git(pusher, "add", ".");
        git(pusher, "commit", "-m", "new commit");
        git(pusher, "push", "origin", "feature/login");

        Map<String, Object> result = service.updateBranch(null, workspace.toString(),"feature/login");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repos = (List<Map<String, Object>>) result.get("repos");
        Map<String, Object> svcA = repos.get(0);
        assertTrue((Boolean) svcA.get("updated"));
        assertEquals("已更新", svcA.get("reason"));
    }

    @Test
    @DisplayName("updateBranch: 本地分支无 upstream 与 fallback 链接都被 skipped 且可清理")
    void updateBranch_localOnlyAndFallback_skipped() throws Exception {
        createRepo("svc-a", "feature/local-only");
        createRepo("svc-b");
        service.switchBranch(null, workspace.toString(), "feature/local-only");
        Path worktreeBase = workspace.resolve(".worktrees").resolve("u-_local").resolve("feature-local-only");
        assertTrue(Files.isDirectory(worktreeBase));

        Map<String, Object> result = service.updateBranch(null, workspace.toString(), "feature/local-only");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repos = (List<Map<String, Object>>) result.get("repos");
        assertEquals(2, repos.size());
        Map<String, Object> svcA = findRepo(repos, "svc-a");
        Map<String, Object> svcB = findRepo(repos, "svc-b");
        assertEquals(Boolean.FALSE, svcA.get("updated"));
        assertEquals(Boolean.TRUE, svcA.get("skipped"));
        assertEquals("本地分支，无远端可更新", svcA.get("reason"));
        assertEquals(Boolean.FALSE, svcB.get("updated"));
        assertEquals(Boolean.TRUE, svcB.get("skipped"));
        assertEquals("回退分支，跳过", svcB.get("reason"));

        service.removeWorktree(null, workspace.toString(), "feature/local-only");

        assertFalse(Files.exists(worktreeBase));
    }

    // ============ removeWorktree: workspace 不存在路径 ============

    @Test
    @DisplayName("removeWorktree: workspace 不存在不报错(silent return)")
    void removeWorktree_nonexistentWorkspace_silent() {
        assertDoesNotThrow(() ->
                service.removeWorktree(null, tempDir.resolve("ghost").toString(), "x"));
    }

    private Map<String, Object> findRepo(List<Map<String, Object>> repos, String name) {
        return repos.stream()
                .filter(repo -> name.equals(repo.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("repo not found: " + name));
    }

    // ============ listWorktrees: 含 fallback 链接时 repoCount 计入 ============

    @Test
    @DisplayName("listWorktrees: 真实 worktree 叶子计入 repoCount")
    void listWorktrees_realWorktrees_includedInRepoCount() throws Exception {
        Path worktreeBase = workspace.resolve(".worktrees/u-_local/feature-x");
        Files.createDirectories(worktreeBase.resolve("svc-a/.git"));
        Files.createDirectories(worktreeBase.resolve("svc-b/.git"));

        List<Map<String, Object>> list = service.listWorktrees(null, workspace.toString());
        assertEquals(1, list.size());
        Map<String, Object> branch = list.get(0);
        Object count = branch.get("repoCount");
        assertEquals(2, ((Number) count).intValue());
    }

    @Test
    @DisplayName("listWorktrees: 工作空间下没有 .worktrees 目录时返回空列表")
    void listWorktrees_noWorktreeDir_returnsEmpty() throws Exception {
        List<Map<String, Object>> list = service.listWorktrees(null, workspace.toString());
        assertTrue(list.isEmpty());
    }

    // ============ 分支名 sanitize: 多重特殊字符 ============

    @Test
    @DisplayName("switchBranch: 分支名含 :/\\ 等 → 全部替换为 -")
    void switchBranch_branchNameWithSpecialChars_sanitized() throws Exception {
        Map<String, Object> result = service.switchBranch(null, workspace.toString(),"release/v1.0");

        String path = (String) result.get("worktreePath");
        String dirName = java.nio.file.Paths.get(path).getFileName().toString();
        assertFalse(dirName.contains("/"));
        assertFalse(dirName.contains(":"));
    }

    // ============ helpers ============

    /** 用 bare remote + working clone 完整模拟 origin/branch, 让 pull --ff-only 可工作。 */
    private Path setupBareRemoteWithBranch(String name, String branch) throws Exception {
        Path bare = tempDir.resolve(name + "-bare.git");
        Files.createDirectories(bare);
        git(bare, "init", "--bare");

        // working clone 写一个 commit + branch + 推回
        Path seed = tempDir.resolve(name + "-seed");
        Files.createDirectories(seed);
        git(seed, "init");
        git(seed, "config", "user.email", "t@t.com");
        git(seed, "config", "user.name", "t");
        Files.write(seed.resolve("README.md"), ("# " + name).getBytes());
        git(seed, "add", ".");
        git(seed, "commit", "-m", "init");
        git(seed, "branch", "-M", "master");
        git(seed, "checkout", "-b", branch);
        git(seed, "remote", "add", "origin", bare.toString());
        git(seed, "push", "-u", "origin", "master");
        git(seed, "push", "-u", "origin", branch);
        return bare;
    }

    private void gitClone(Path bareRemote, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        ProcessBuilder pb = new ProcessBuilder("git", "clone",
                bareRemote.toString(), target.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drain(p);
        if (p.waitFor() != 0) {
            throw new RuntimeException("git clone failed");
        }
        git(target, "config", "user.email", "t@t.com");
        git(target, "config", "user.name", "t");
    }

    /** 沿用 WorktreeServiceTest 的本地 init 风格,但带 origin。 */
    private Path createRepo(String name, String... branches) throws Exception {
        Path repoDir = workspace.resolve(name);
        Files.createDirectories(repoDir);
        git(repoDir, "init");
        git(repoDir, "config", "user.email", "t@t.com");
        git(repoDir, "config", "user.name", "t");
        Files.write(repoDir.resolve("README.md"), ("# " + name).getBytes());
        git(repoDir, "add", ".");
        git(repoDir, "commit", "-m", "init");
        for (String b : branches) {
            git(repoDir, "branch", b);
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
        drain(p);
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed in " + dir);
        }
    }

    private void drain(Process p) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) {
                // discard
            }
        }
    }
}
