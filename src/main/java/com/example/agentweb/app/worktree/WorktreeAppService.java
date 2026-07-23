package com.example.agentweb.app.worktree;

import com.example.agentweb.domain.worktree.UserBranchRef;
import com.example.agentweb.domain.worktree.UserSlug;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.domain.worktree.WorkspaceUploadRoot;
import com.example.agentweb.domain.worktree.WorktreeDirName;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages git worktrees for branch-level isolation across a workspace
 * containing multiple independent git repositories.
 *
 * <p>多租户隔离: 每个用户的 worktree 落在私有桶 {@code .worktrees/u-{userSlug}/{safeBranch}},
 * 并 checkout 私有 ref {@code wt/{userSlug}/{logicalBranch}} —— 共享对象库下同名逻辑分支被命名空间化,
 * 多用户各持私有 ref, 互不冲突。</p>
 *
 * <p>本类只做编排: 规则(分支净化 / 私有 ref 判定)在 {@code domain/worktree},
 * git 子进程与文件系统副作用分别经 {@link GitWorktreeGateway} / {@link WorktreeFileGateway} 两个端口出层。</p>
 *
 * @author zhourui(V33215020)
 */
@Service
public class WorktreeAppService {

    /** worktree 子树目录名,与 {@link WorkspaceUploadRoot#WORKTREE_DIR} 同源,避免约定在两处漂移。 */
    private static final String WORKTREE_DIR = WorkspaceUploadRoot.WORKTREE_DIR;
    /** 每用户隔离桶目录前缀: {@code .worktrees/u-{userSlug}/{safeBranch}}。 */
    private static final String USER_BUCKET_PREFIX = "u-";
    private static final String AGENT_GUIDANCE_FILE = "AGENTS.md";
    private static final int FETCH_TIMEOUT_SECONDS = 30;
    private static final int THREAD_POOL_SIZE = 8;

    private final WorkspacePathPolicy workspacePathPolicy;
    private final GitWorktreeGateway gitGateway;
    private final WorktreeFileGateway fileGateway;

    /**
     * 所有 worktree 操作复用统一工作空间真实路径校验端口。
     *
     * @param workspacePathPolicy 工作空间路径端口
     * @param gitGateway          git 子进程端口
     * @param fileGateway         文件系统端口
     */
    public WorktreeAppService(WorkspacePathPolicy workspacePathPolicy,
                              GitWorktreeGateway gitGateway,
                              WorktreeFileGateway fileGateway) {
        this.workspacePathPolicy = Objects.requireNonNull(workspacePathPolicy, "workspacePathPolicy");
        this.gitGateway = Objects.requireNonNull(gitGateway, "gitGateway");
        this.fileGateway = Objects.requireNonNull(fileGateway, "fileGateway");
    }

    /**
     * Switch a workspace to a given branch by creating worktrees for every
     * git repo under {@code workspacePath}. Repos that have the target branch
     * get a real worktree on it; repos that don't fall back to the default
     * branch (origin/HEAD, else current HEAD) via a symlink to the main repo.
     */
    public WorktreeSwitchView switchBranch(String userId, String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = requireWorkspace(workspacePath);

        String userSlug = UserSlug.slug(userId);
        WorktreeDirName dirName = WorktreeDirName.fromBranch(branch);
        Path worktreeBase = dirName.resolveWithin(userBucket(workspace, userSlug));
        fileGateway.createDirectories(worktreeBase);
        fileGateway.copyFileIfPresent(workspace.resolve(AGENT_GUIDANCE_FILE), worktreeBase);

        List<GitRepoEntry> gitRepos = fileGateway.collectGitRepos(workspace);

        List<File> repoDirs = new ArrayList<>();
        for (GitRepoEntry e : gitRepos) {
            repoDirs.add(e.dir());
        }
        parallelFetch(repoDirs);

        List<WorktreeRepoSwitchView> repos = new ArrayList<>();
        for (GitRepoEntry entry : gitRepos) {
            repos.add(createWorktreeForRepo(entry, branch, worktreeBase, userSlug));
        }

        return new WorktreeSwitchView(worktreeBase.toString(), branch, repos);
    }

    private void parallelFetch(List<File> repos) throws InterruptedException {
        if (repos.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(THREAD_POOL_SIZE, repos.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (File repo : repos) {
                if (!fileGateway.hasConfiguredRemote(repo)) {
                    continue;
                }
                futures.add(pool.submit(() -> {
                    try {
                        gitGateway.fetchAll(repo, FETCH_TIMEOUT_SECONDS);
                    } catch (Exception ignored) {
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(FETCH_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Pull latest commits into every real worktree of {@code branch}. Symlink /
     * junction leaves point into the main repo checkout; pulling those would
     * move the user's in-progress HEAD, so skip them with a clear reason.
     */
    public WorktreeUpdateView updateBranch(String userId, String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = requireWorkspace(workspacePath);
        String userSlug = UserSlug.slug(userId);
        WorktreeDirName dirName = WorktreeDirName.fromBranch(branch);
        Path worktreeBase = dirName.resolveWithin(userBucket(workspace, userSlug));
        if (!fileGateway.isDirectory(worktreeBase)) {
            throw new IllegalArgumentException("Branch worktree not found: " + branch);
        }

        WorktreeLeaves leaves = fileGateway.classifyLeaves(worktreeBase);
        List<WorktreeRepoUpdateView> repos = parallelPull(worktreeBase, leaves.realWorktrees());
        for (Path link : leaves.links()) {
            repos.add(new WorktreeRepoUpdateView(
                    worktreeBase.relativize(link).toString(), false, true, "回退分支，跳过"));
        }
        repos.sort(Comparator.comparing(WorktreeRepoUpdateView::name));

        return new WorktreeUpdateView(branch, repos);
    }

    private List<WorktreeRepoUpdateView> parallelPull(Path worktreeBase, List<Path> repos)
            throws InterruptedException {
        List<WorktreeRepoUpdateView> results = new ArrayList<>();
        if (repos.isEmpty()) {
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(THREAD_POOL_SIZE, repos.size()));
        try {
            List<Future<WorktreeRepoUpdateView>> futures = new ArrayList<>();
            for (Path repo : repos) {
                final String name = worktreeBase.relativize(repo).toString();
                futures.add(pool.submit(() -> pullOne(name, repo.toFile())));
            }
            for (int i = 0; i < futures.size(); i++) {
                String name = worktreeBase.relativize(repos.get(i)).toString();
                try {
                    results.add(futures.get(i).get(FETCH_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));
                } catch (ExecutionException | TimeoutException ex) {
                    results.add(new WorktreeRepoUpdateView(name, false, null,
                            ex instanceof TimeoutException ? "超时" : "执行异常"));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /**
     * Run {@code git pull --ff-only} and classify the outcome by comparing HEAD
     * before/after — locale-independent, works regardless of git's language.
     */
    private WorktreeRepoUpdateView pullOne(String name, File repoDir) {
        try {
            if (!gitGateway.hasUpstream(repoDir)) {
                // 本地私有分支(从未 push 到 origin)无 upstream, pull --ff-only 会报错; 按良性跳过, 不泄漏英文报错
                return new WorktreeRepoUpdateView(name, false, true, "本地分支，无远端可更新");
            }
            String before = gitGateway.headCommit(repoDir);
            GitExecResult pull = gitGateway.pullFastForwardOnly(repoDir);
            if (!pull.isSuccess()) {
                String msg = pull.output().trim();
                return new WorktreeRepoUpdateView(name, false, null, msg.isEmpty() ? "更新失败" : msg);
            }
            String after = gitGateway.headCommit(repoDir);
            return new WorktreeRepoUpdateView(name, true, null, pullSuccessReason(before, after));
        } catch (Exception ex) {
            return new WorktreeRepoUpdateView(name, false, null,
                    ex.getMessage() == null ? "异常" : ex.getMessage());
        }
    }

    static String pullSuccessReason(String before, String after) {
        return before.equals(after) ? "已是最新" : "已更新";
    }

    /**
     * List the current user's worktree branch directories under {@code .worktrees/u-{slug}}.
     * The public bucket ({@code _local}) additionally exposes legacy flat directories
     * ({@code .worktrees/{branch}}) created before per-user isolation existed.
     */
    public List<WorktreeBranchView> listWorktrees(String userId, String workspacePath) throws IOException {
        Path workspace = prepareWorkspace(workspacePath);
        Path worktreeRoot = workspace.resolve(WORKTREE_DIR);
        List<WorktreeBranchView> result = new ArrayList<>();
        if (!fileGateway.isDirectory(worktreeRoot)) {
            return result;
        }

        String userSlug = UserSlug.slug(userId);
        collectBranchDirs(worktreeRoot.resolve(USER_BUCKET_PREFIX + userSlug), result);
        if (UserSlug.LOCAL_BUCKET.equals(userSlug)) {
            // 老布局与同名 sanitized 桶目录可能撞名, 以桶目录为准去重, 避免前端按 branch 名误删
            Set<String> seenBranches = new HashSet<>();
            for (WorktreeBranchView item : result) {
                seenBranches.add(item.branch());
            }
            collectLegacyBranchDirs(worktreeRoot, result, seenBranches);
        }
        return result;
    }

    private void collectBranchDirs(Path bucketDir, List<WorktreeBranchView> result) throws IOException {
        if (!fileGateway.isDirectory(bucketDir)) {
            return;
        }
        File[] branches = bucketDir.toFile().listFiles();
        if (branches == null) {
            return;
        }
        Arrays.sort(branches);
        for (File branchDir : branches) {
            if (branchDir.isDirectory()) {
                result.add(branchItem(branchDir));
            }
        }
    }

    /** 老布局裸分支目录(无 {@code u-} 前缀), 仅公共桶可见, 兼容 P1 之前创建的 worktree; 与桶内同名项去重。 */
    private void collectLegacyBranchDirs(Path worktreeRoot, List<WorktreeBranchView> result,
                                         Set<String> seenBranches) throws IOException {
        File[] entries = worktreeRoot.toFile().listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries);
        for (File entry : entries) {
            if (entry.isDirectory()
                    && !entry.getName().startsWith(USER_BUCKET_PREFIX)
                    && !seenBranches.contains(entry.getName())) {
                result.add(branchItem(entry));
            }
        }
    }

    private WorktreeBranchView branchItem(File branchDir) throws IOException {
        WorktreeLeaves leaves = fileGateway.classifyLeaves(branchDir.toPath());
        return new WorktreeBranchView(branchDir.getName(), branchDir.getAbsolutePath(), leaves.totalCount());
    }

    /**
     * Remove all worktrees for a branch and clean up the directory, then recycle the
     * user's private refs so {@code wt/{slug}/*} does not accumulate unboundedly.
     */
    public void removeWorktree(String userId, String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = prepareWorkspace(workspacePath);
        String userSlug = UserSlug.slug(userId);
        WorktreeDirName dirName = WorktreeDirName.fromBranch(branch);
        Path userBase = dirName.resolveWithin(userBucket(workspace, userSlug));
        // 新布局缺失时回退老布局 .worktrees/{branch}, 兼容 P1 之前创建的 worktree
        Path legacyBase = dirName.resolveWithin(workspace.resolve(WORKTREE_DIR));
        final Path worktreeBase = fileGateway.isDirectory(userBase) ? userBase : legacyBase;
        if (!fileGateway.isDirectory(worktreeBase)) {
            return;
        }

        WorktreeLeaves leaves = fileGateway.classifyLeaves(worktreeBase);
        for (Path link : leaves.links()) {
            fileGateway.delete(link);
        }
        for (Path wt : leaves.realWorktrees()) {
            Path originalRepo = workspace.resolve(worktreeBase.relativize(wt));
            if (fileGateway.isDirectory(originalRepo) && fileGateway.exists(originalRepo.resolve(".git"))) {
                // 删除前取该 worktree 实际检出的分支, 才能精确回收私有 ref(覆盖 fallback 默认分支)
                String checkedOutRef = gitGateway.currentBranchRef(wt);
                gitGateway.removeWorktree(originalRepo.toFile(), wt);
                recyclePrivateRef(originalRepo.toFile(), checkedOutRef);
            }
        }

        fileGateway.deleteRecursively(worktreeBase);
    }

    /**
     * 回收私有 ref, best-effort。不变量由 {@link UserBranchRef#isNamespaced} 守护:
     * 仅删 {@code wt/} 前缀的私有分支, 绝不误删真实业务分支;
     * {@code git branch -D} 失败(如 fallback 仓为 symlink 无私有 ref)无害忽略。
     */
    private void recyclePrivateRef(File repoDir, String checkedOutRef)
            throws IOException, InterruptedException {
        if (UserBranchRef.isNamespaced(checkedOutRef)) {
            gitGateway.deleteBranch(repoDir, checkedOutRef);
        }
    }

    // ---- internal helpers ----

    private Path userBucket(Path workspace, String userSlug) {
        return workspace.resolve(WORKTREE_DIR).resolve(USER_BUCKET_PREFIX + userSlug);
    }

    private Path requireWorkspace(String workspacePath) {
        return Paths.get(workspacePathPolicy.requireExistingDirectory(workspacePath));
    }

    private Path prepareWorkspace(String workspacePath) {
        return Paths.get(workspacePathPolicy.prepareWorkspaceDirectory(workspacePath));
    }

    private WorktreeRepoSwitchView createWorktreeForRepo(GitRepoEntry entry, String branch,
                                                         Path worktreeBase, String userSlug)
            throws IOException, InterruptedException {

        File repoDir = entry.dir();
        String name = entry.relativePath().toString();

        boolean localExists = gitGateway.localBranchExists(repoDir, branch);
        boolean remoteExists = gitGateway.remoteBranchExists(repoDir, branch);

        String logicalBranch;
        boolean isFallback;
        if (localExists || remoteExists) {
            logicalBranch = branch;
            isFallback = false;
        } else {
            logicalBranch = gitGateway.defaultBranch(repoDir);
            isFallback = true;
        }

        Path repoWorktree = worktreeBase.resolve(entry.relativePath());
        if (fileGateway.existsNoFollowLinks(repoWorktree)) {
            return new WorktreeRepoSwitchView(name, logicalBranch, true, "已存在");
        }
        Path parent = repoWorktree.getParent();
        if (parent != null) {
            fileGateway.createDirectories(parent);
        }

        // Fallback 仓(无业务分支): 有远端默认分支 → 也建私有 worktree 隔离, 否则多用户 symlink 到同一主仓
        // 检出会被 agent 跨用户写污染。仅"纯本地无远端"的退化测试库保留 symlink 复用(无可隔离的远端真相源)。
        if (isFallback && !fileGateway.hasConfiguredRemote(repoDir)) {
            fileGateway.createDirectoryLink(repoWorktree, repoDir.toPath());
            return new WorktreeRepoSwitchView(name, logicalBranch, true, "无此分支，复用默认分支路径");
        }

        return addPrivateWorktree(repoDir, repoWorktree, userSlug, logicalBranch,
                remoteExists, isFallback, name);
    }

    /**
     * 为当前用户建私有 ref {@code wt/{slug}/{logical}} 的 worktree。共享对象库下同名逻辑分支被
     * 命名空间化, 多用户各持私有 ref, checkout 互不冲突。二次进入(ref 残留)时复用而非重复 -b。
     */
    private WorktreeRepoSwitchView addPrivateWorktree(File repoDir, Path repoWorktree, String userSlug,
                                                      String logicalBranch, boolean remoteExists,
                                                      boolean isFallback, String name)
            throws IOException, InterruptedException {

        String privateRef = UserBranchRef.of(userSlug, logicalBranch).namespacedRef();
        String startPoint;
        if (gitGateway.localBranchExists(repoDir, privateRef)) {
            String existingPrivate = gitGateway.findCheckoutPath(repoDir, privateRef);
            if (existingPrivate != null && fileGateway.isDirectory(Paths.get(existingPrivate))) {
                fileGateway.createDirectoryLink(repoWorktree, Paths.get(existingPrivate));
                return new WorktreeRepoSwitchView(name, logicalBranch, true, "复用已检出路径");
            }
            if (existingPrivate != null) {
                gitGateway.pruneWorktrees(repoDir);
            }
            startPoint = null;
        } else {
            startPoint = remoteExists ? "origin/" + logicalBranch : logicalBranch;
        }

        GitExecResult er = gitGateway.addWorktree(repoDir, repoWorktree, privateRef, startPoint);
        if (!er.isSuccess() && gitGateway.isStaleWorktreeError(er.output())) {
            gitGateway.pruneWorktrees(repoDir);
            er = gitGateway.addWorktree(repoDir, repoWorktree, privateRef, startPoint);
        }
        if (!er.isSuccess()) {
            String msg = er.output().trim();
            return new WorktreeRepoSwitchView(name, logicalBranch, false,
                    msg.isEmpty() ? "创建失败" : msg);
        }
        return new WorktreeRepoSwitchView(name, logicalBranch, true,
                isFallback ? "无此分支，已回退到默认分支" : null);
    }
}
