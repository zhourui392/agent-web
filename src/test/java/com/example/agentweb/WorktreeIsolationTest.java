package com.example.agentweb;

import com.example.agentweb.app.WorktreeService;
import com.example.agentweb.domain.worktree.UserBranchRef;
import com.example.agentweb.domain.worktree.UserSlug;
import com.example.agentweb.infra.FsProperties;
import com.example.agentweb.infra.RealPathWorkspacePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 worktree 多租户隔离 + 安全回归: 同对象库下多用户切同名分支各持私有 ref;
 * fallback 仓(有远端)也隔离; removeWorktree 精确回收私有 ref 且不误删真实分支;
 * 目录穿越被拒; 陈旧注册自愈。真 git 子进程, 不起 Spring。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Tag("git-integration")
class WorktreeIsolationTest {

    @TempDir
    Path tempDir;

    private WorktreeService service;
    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        FsProperties properties = new FsProperties();
        properties.getRoots().add(tempDir.toString());
        service = new WorktreeService(new RealPathWorkspacePolicy(properties));
        workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
    }

    @Test
    @DisplayName("两个用户切同一逻辑分支: 都成功, worktree 路径不同, 私有 ref 不同且均存在")
    void twoUsers_sameBranch_isolatedRefs() throws Exception {
        Path repo = createRepo("svc-a", "feature/login");
        String userA = "ou_alice";
        String userB = "ou_bob";
        String slugA = UserSlug.slug(userA);
        String slugB = UserSlug.slug(userB);

        Map<String, Object> resA = service.switchBranch(userA, workspace.toString(), "feature/login");
        Map<String, Object> resB = service.switchBranch(userB, workspace.toString(), "feature/login");

        assertNotEquals(resA.get("worktreePath"), resB.get("worktreePath"));
        assertTrue(firstRepoCreated(resA), "userA 应创建成功");
        assertTrue(firstRepoCreated(resB), "userB 应创建成功");

        String refA = UserBranchRef.of(slugA, "feature/login").namespacedRef();
        String refB = UserBranchRef.of(slugB, "feature/login").namespacedRef();
        assertNotEquals(refA, refB);
        assertEquals(0, gitRc(repo, "show-ref", "--verify", "--quiet", "refs/heads/" + refA));
        assertEquals(0, gitRc(repo, "show-ref", "--verify", "--quiet", "refs/heads/" + refB));

        service.removeWorktree(userA, workspace.toString(), "feature/login");

        assertNotEquals(0, gitRc(repo, "show-ref", "--verify", "--quiet", "refs/heads/" + refA),
                "remove 后 userA 私有 ref 应被回收");
        assertEquals(0, gitRc(repo, "show-ref", "--verify", "--quiet", "refs/heads/" + refB),
                "userB 私有 ref 不应被误删");
        assertFalse(Files.exists(workspace.resolve(".worktrees").resolve("u-" + slugA)
                .resolve("feature-login")));
    }

    @Test
    @DisplayName("安全: branch=\"..\" 被拒, 绝不波及兄弟租户桶或 .worktrees 根")
    void pathTraversalBranch_rejected_doesNotTouchSiblingBuckets() throws Exception {
        Path root = workspace.resolve(".worktrees");
        Path aliceBucket = root.resolve("u-" + UserSlug.slug("ou_alice"));
        Path bobBucket = root.resolve("u-" + UserSlug.slug("ou_bob"));
        Files.createDirectories(aliceBucket);
        Files.createDirectories(bobBucket);

        assertThrows(IllegalArgumentException.class,
                () -> service.removeWorktree("ou_attacker", workspace.toString(), ".."));

        assertTrue(Files.isDirectory(aliceBucket), "alice 桶不应被删");
        assertTrue(Files.isDirectory(bobBucket), "bob 桶不应被删");
        assertTrue(Files.isDirectory(root), ".worktrees 根不应被删");
    }

    @Test
    @DisplayName("fallback 仓(有远端): 创建隔离私有 worktree, 不共享主仓 checkout")
    void fallbackRepoWithRemote_isolated() throws Exception {
        Path bare = setupBareRemoteMasterOnly("dep");
        Path repo = workspace.resolve("dep");
        gitClone(bare, repo);
        git(repo, "fetch", "--all");

        Map<String, Object> resA = service.switchBranch("ou_alice", workspace.toString(), "feature/login");

        Path aliceRepo = Paths.get((String) resA.get("worktreePath"), "dep");
        assertFalse(isDirectoryLink(aliceRepo), "alice 的 fallback 仓应是隔离 worktree 而非共享链接");
        assertEquals(0, gitRc(repo, "show-ref", "--verify", "--quiet",
                "refs/heads/wt/" + UserSlug.slug("ou_alice") + "/master"));
    }

    @Test
    @DisplayName("老布局真分支 worktree: remove 删目录但绝不误删真实逻辑分支")
    void removeWorktree_legacyLayout_keepsRealBranch() throws Exception {
        Path repo = createRepo("svc-a", "feature/login");
        // 手工在老布局 .worktrees/{branch} 检出真实逻辑分支(不经 switchBranch, 无 u- 桶, 无私有 ref)
        Path legacy = workspace.resolve(".worktrees").resolve("feature-login").resolve("svc-a");
        Files.createDirectories(legacy.getParent());
        git(repo, "worktree", "add", legacy.toString(), "feature/login");
        assertEquals(0, gitRc(repo, "show-ref", "--verify", "--quiet", "refs/heads/feature/login"));

        service.removeWorktree(null, workspace.toString(), "feature/login");

        assertFalse(Files.exists(workspace.resolve(".worktrees").resolve("feature-login")),
                "老布局目录应删除");
        assertEquals(0, gitRc(repo, "show-ref", "--verify", "--quiet", "refs/heads/feature/login"),
                "真实逻辑分支不应被误删(recyclePrivateRef 只删 wt/ 前缀)");
    }

    @Test
    @DisplayName("陈旧注册自愈: 目录被外部删但私有 ref 残留时, 再次 switch 不卡死")
    void reEntry_staleRegistration_selfHeals() throws Exception {
        createRepo("svc-a", "feature/login");
        Map<String, Object> first = service.switchBranch("ou_alice", workspace.toString(), "feature/login");
        Path wtDir = Paths.get((String) first.get("worktreePath"), "svc-a");
        assertTrue(Files.isDirectory(wtDir));

        // 模拟磁盘清理/失败的 remove: 目录没了但 git 注册与私有 ref 仍残留
        rmRf(wtDir);

        Map<String, Object> second = service.switchBranch("ou_alice", workspace.toString(), "feature/login");
        assertTrue(firstRepoCreated(second), "二次进入应 prune 自愈: " + firstRepoReason(second));
        assertTrue(Files.isDirectory(wtDir), "worktree 目录应被重建");
    }

    // ============ helpers ============

    @SuppressWarnings("unchecked")
    private boolean firstRepoCreated(Map<String, Object> result) {
        return Boolean.TRUE.equals(
                ((java.util.List<Map<String, Object>>) result.get("repos")).get(0).get("created"));
    }

    @SuppressWarnings("unchecked")
    private Object firstRepoReason(Map<String, Object> result) {
        return ((java.util.List<Map<String, Object>>) result.get("repos")).get(0).get("reason");
    }

    private Path createRepo(String name, String... branches) throws Exception {
        Path repoDir = workspace.resolve(name);
        Files.createDirectories(repoDir);
        git(repoDir, "init");
        git(repoDir, "config", "user.email", "test@test.com");
        git(repoDir, "config", "user.name", "test");
        Files.write(repoDir.resolve("README.md"), ("# " + name).getBytes());
        git(repoDir, "add", ".");
        git(repoDir, "commit", "-m", "init");
        for (String b : branches) {
            git(repoDir, "branch", b);
        }
        return repoDir;
    }

    /** bare remote 仅含 master, 供 fallback-有远端 场景: clone 后该仓无业务分支但有 origin/master。 */
    private Path setupBareRemoteMasterOnly(String name) throws Exception {
        Path bare = tempDir.resolve(name + "-bare.git");
        Files.createDirectories(bare);
        git(bare, "init", "--bare");
        Path seed = tempDir.resolve(name + "-seed");
        Files.createDirectories(seed);
        git(seed, "init");
        git(seed, "config", "user.email", "t@t.com");
        git(seed, "config", "user.name", "t");
        Files.write(seed.resolve("README.md"), ("# " + name).getBytes());
        git(seed, "add", ".");
        git(seed, "commit", "-m", "init");
        git(seed, "branch", "-M", "master");
        git(seed, "remote", "add", "origin", bare.toString());
        git(seed, "push", "-u", "origin", "master");
        return bare;
    }

    private void gitClone(Path bareRemote, Path target) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "clone", bareRemote.toString(), target.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drain(p);
        if (p.waitFor() != 0) {
            throw new RuntimeException("git clone failed");
        }
        git(target, "config", "user.email", "t@t.com");
        git(target, "config", "user.name", "t");
    }

    private void rmRf(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
    }

    /** 跨平台目录链接检测: Unix symlink 或 Windows NTFS junction。 */
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
                Path expectedReal = parent.toRealPath().resolve(path.getFileName());
                return !path.toRealPath().equals(expectedReal);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private void git(Path dir, String... args) throws Exception {
        if (gitRc(dir, args) != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed in " + dir);
        }
    }

    /** 执行 git 并返回退出码, 不抛异常 (供 show-ref 之类存在性断言)。 */
    private int gitRc(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drain(p);
        return p.waitFor();
    }

    private void drain(Process p) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) {
                // discard
            }
        }
    }
}
