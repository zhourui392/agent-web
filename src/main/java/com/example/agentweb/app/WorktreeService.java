package com.example.agentweb.app;

import com.example.agentweb.domain.worktree.UserBranchRef;
import com.example.agentweb.domain.worktree.UserSlug;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.domain.worktree.WorkspaceUploadRoot;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * @author zhourui(V33215020)
 */
@Service
public class WorktreeService {

    /** worktree 子树目录名,与 {@link WorkspaceUploadRoot#WORKTREE_DIR} 同源,避免约定在两处漂移。 */
    private static final String WORKTREE_DIR = WorkspaceUploadRoot.WORKTREE_DIR;
    /** 每用户隔离桶目录前缀: {@code .worktrees/u-{userSlug}/{safeBranch}}。 */
    private static final String USER_BUCKET_PREFIX = "u-";
    private static final String AGENT_GUIDANCE_FILE = "AGENTS.md";
    private static final int FETCH_TIMEOUT_SECONDS = 30;
    private static final int THREAD_POOL_SIZE = 8;
    /**
     * Depth relative to workspace root: root=0, direct child=1. A value of 4
     * lets us find repos at workspace/a/b/c/repo.
     */
    private static final int MAX_SCAN_DEPTH = 4;

    private static final String CURRENT_DIR = ".";
    private static final String GIT_DIR = ".git";
    private static final String ORIGIN_REF_PREFIX = "origin/";
    private static final String LF = "\n";

    private final WorkspacePathPolicy workspacePathPolicy;

    /**
     * 所有 worktree 操作复用统一工作空间真实路径校验端口。
     *
     * @param workspacePathPolicy 工作空间路径端口
     */
    public WorktreeService(WorkspacePathPolicy workspacePathPolicy) {
        this.workspacePathPolicy = Objects.requireNonNull(workspacePathPolicy, "workspacePathPolicy");
    }

    /**
     * Switch a workspace to a given branch by creating worktrees for every
     * git repo under {@code workspacePath}. Repos that have the target branch
     * get a real worktree on it; repos that don't fall back to the default
     * branch (origin/HEAD, else current HEAD) via a symlink to the main repo.
     */
    public Map<String, Object> switchBranch(String userId, String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = requireWorkspace(workspacePath);

        String userSlug = UserSlug.slug(userId);
        String safeBranch = safeBranchName(branch);
        Path worktreeBase = resolveWithinBucket(userBucket(workspace, userSlug), safeBranch);
        Files.createDirectories(worktreeBase);
        syncAgentGuidance(workspace, worktreeBase);

        List<RepoEntry> gitRepos = collectGitRepos(workspace);

        List<File> repoDirs = new ArrayList<>();
        for (RepoEntry e : gitRepos) {
            repoDirs.add(e.dir);
        }
        parallelFetch(repoDirs);

        List<Map<String, Object>> repos = new ArrayList<>();
        for (RepoEntry entry : gitRepos) {
            repos.add(createWorktreeForRepo(entry, branch, worktreeBase, userSlug));
        }

        Map<String, Object> result = new HashMap<>(16);
        result.put("worktreePath", worktreeBase.toString());
        result.put("branch", branch);
        result.put("repos", repos);
        return result;
    }

    private List<RepoEntry> collectGitRepos(Path workspace) throws IOException {
        List<RepoEntry> repos = new ArrayList<>();
        Files.walkFileTree(workspace, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                MAX_SCAN_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(workspace)) {
                    return FileVisitResult.CONTINUE;
                }
                // Skip hidden dirs (.git, .worktrees, .idea, ...).
                if (dir.getFileName().toString().startsWith(CURRENT_DIR)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.exists(dir.resolve(GIT_DIR))) {
                    repos.add(new RepoEntry(dir.toFile(), workspace.relativize(dir)));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        repos.sort(Comparator.comparing(r -> r.relativePath.toString()));
        return repos;
    }

    private void syncAgentGuidance(Path workspace, Path worktreeBase) throws IOException {
        Path source = workspace.resolve(AGENT_GUIDANCE_FILE);
        if (!Files.isRegularFile(source)) {
            return;
        }
        Files.copy(source, worktreeBase.resolve(AGENT_GUIDANCE_FILE),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
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
                if (!hasConfiguredRemote(repo)) {
                    continue;
                }
                futures.add(pool.submit(() -> {
                    try {
                        execWithTimeout(repo, FETCH_TIMEOUT_SECONDS,
                                "git", "fetch", "--all", "--prune");
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

    private boolean hasConfiguredRemote(File repoDir) {
        Path gitPath = repoDir.toPath().resolve(GIT_DIR);
        if (!Files.isDirectory(gitPath)) {
            return true;
        }
        Path config = gitPath.resolve("config");
        if (!Files.isRegularFile(config)) {
            return true;
        }
        try {
            return Files.readAllLines(config).stream()
                    .anyMatch(line -> line.trim().startsWith("[remote "));
        } catch (IOException ex) {
            return true;
        }
    }

    /**
     * Pull latest commits into every real worktree of {@code branch}. Symlink /
     * junction leaves point into the main repo checkout; pulling those would
     * move the user's in-progress HEAD, so skip them with a clear reason.
     */
    public Map<String, Object> updateBranch(String userId, String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = requireWorkspace(workspacePath);
        String userSlug = UserSlug.slug(userId);
        String safeBranch = safeBranchName(branch);
        Path worktreeBase = resolveWithinBucket(userBucket(workspace, userSlug), safeBranch);
        if (!Files.isDirectory(worktreeBase)) {
            throw new IllegalArgumentException("Branch worktree not found: " + branch);
        }

        LeafScan scan = classifyLeaves(worktreeBase);
        List<Map<String, Object>> repos = parallelPull(worktreeBase, scan.realWorktrees);
        for (Path link : scan.links) {
            Map<String, Object> r = new HashMap<>(16);
            r.put("name", worktreeBase.relativize(link).toString());
            r.put("updated", false);
            r.put("skipped", true);
            r.put("reason", "回退分支，跳过");
            repos.add(r);
        }
        repos.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));

        Map<String, Object> result = new HashMap<>(16);
        result.put("branch", branch);
        result.put("repos", repos);
        return result;
    }

    private List<Map<String, Object>> parallelPull(Path worktreeBase, List<Path> repos)
            throws InterruptedException {
        List<Map<String, Object>> results = new ArrayList<>();
        if (repos.isEmpty()) {
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(THREAD_POOL_SIZE, repos.size()));
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (Path repo : repos) {
                final String name = worktreeBase.relativize(repo).toString();
                futures.add(pool.submit(() -> pullOne(name, repo.toFile())));
            }
            for (int i = 0; i < futures.size(); i++) {
                String name = worktreeBase.relativize(repos.get(i)).toString();
                try {
                    results.add(futures.get(i).get(FETCH_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));
                } catch (ExecutionException | TimeoutException ex) {
                    Map<String, Object> r = new HashMap<>(16);
                    r.put("name", name);
                    r.put("updated", false);
                    r.put("reason", ex instanceof TimeoutException ? "超时" : "执行异常");
                    results.add(r);
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
    private Map<String, Object> pullOne(String name, File repoDir) {
        Map<String, Object> r = new HashMap<>(16);
        r.put("name", name);
        try {
            if (!hasUpstream(repoDir)) {
                // 本地私有分支(从未 push 到 origin)无 upstream, pull --ff-only 会报错; 按良性跳过, 不泄漏英文报错
                r.put("updated", false);
                r.put("skipped", true);
                r.put("reason", "本地分支，无远端可更新");
                return r;
            }
            String before = execCapture(repoDir, "git", "rev-parse", "HEAD").output.trim();
            ExecResult pull = execCapture(repoDir, "git", "pull", "--ff-only");
            if (pull.exitCode != 0) {
                r.put("updated", false);
                String msg = pull.output.trim();
                r.put("reason", msg.isEmpty() ? "更新失败" : msg);
                return r;
            }
            String after = execCapture(repoDir, "git", "rev-parse", "HEAD").output.trim();
            r.put("updated", true);
            r.put("reason", pullSuccessReason(before, after));
        } catch (Exception ex) {
            r.put("updated", false);
            r.put("reason", ex.getMessage() == null ? "异常" : ex.getMessage());
        }
        return r;
    }

    private String pullSuccessReason(String before, String after) {
        return before.equals(after) ? "已是最新" : "已更新";
    }

    /**
     * List the current user's worktree branch directories under {@code .worktrees/u-{slug}}.
     * The public bucket ({@code _local}) additionally exposes legacy flat directories
     * ({@code .worktrees/{branch}}) created before per-user isolation existed.
     */
    public List<Map<String, Object>> listWorktrees(String userId, String workspacePath) throws IOException {
        Path workspace = prepareWorkspace(workspacePath);
        Path worktreeRoot = workspace.resolve(WORKTREE_DIR);
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(worktreeRoot)) {
            return result;
        }

        String userSlug = UserSlug.slug(userId);
        collectBranchDirs(worktreeRoot.resolve(USER_BUCKET_PREFIX + userSlug), result);
        if (UserSlug.LOCAL_BUCKET.equals(userSlug)) {
            // 老布局与同名 sanitized 桶目录可能撞名, 以桶目录为准去重, 避免前端按 branch 名误删
            Set<String> seenBranches = new HashSet<>();
            for (Map<String, Object> item : result) {
                seenBranches.add(String.valueOf(item.get("branch")));
            }
            collectLegacyBranchDirs(worktreeRoot, result, seenBranches);
        }
        return result;
    }

    private void collectBranchDirs(Path bucketDir, List<Map<String, Object>> result) throws IOException {
        if (!Files.isDirectory(bucketDir)) {
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
    private void collectLegacyBranchDirs(Path worktreeRoot, List<Map<String, Object>> result,
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

    private Map<String, Object> branchItem(File branchDir) throws IOException {
        Map<String, Object> item = new HashMap<>(16);
        item.put("branch", branchDir.getName());
        item.put("path", branchDir.getAbsolutePath());
        item.put("repoCount", countRepoLeaves(branchDir.toPath()));
        return item;
    }

    /**
     * Count repo leaves under a branch worktree root. A leaf is either a
     * symlink/junction (fallback path) or a directory containing {@code .git}.
     */
    private int countRepoLeaves(Path base) throws IOException {
        LeafScan scan = classifyLeaves(base);
        return scan.links.size() + scan.realWorktrees.size();
    }

    /** worktree base 下一次遍历的分类结果: 链接叶子(fallback) 与 真实 worktree(含 .git)。 */
    private static final class LeafScan {
        final List<Path> links = new ArrayList<>();
        final List<Path> realWorktrees = new ArrayList<>();
    }

    /**
     * 遍历 worktree base, 把叶子分为链接(symlink/NTFS junction, fallback 复用)与真实 worktree。
     * updateBranch / removeWorktree / countRepoLeaves 共用, 消除三份逐字相同的匿名 visitor。
     */
    private LeafScan classifyLeaves(Path base) throws IOException {
        LeafScan scan = new LeafScan();
        Files.walkFileTree(base, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                MAX_SCAN_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(base)) {
                    return FileVisitResult.CONTINUE;
                }
                // NTFS junctions masquerade as regular directories to walkFileTree;
                // detect them here so we don't descend into the target checkout.
                if (isDirectoryLink(dir)) {
                    scan.links.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.exists(dir.resolve(GIT_DIR))) {
                    scan.realWorktrees.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink()) {
                    scan.links.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return scan;
    }

    /**
     * Remove all worktrees for a branch and clean up the directory, then recycle the
     * user's private refs so {@code wt/{slug}/*} does not accumulate unboundedly.
     */
    public void removeWorktree(String userId, String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = prepareWorkspace(workspacePath);
        String userSlug = UserSlug.slug(userId);
        String safeBranch = safeBranchName(branch);
        Path userBase = resolveWithinBucket(userBucket(workspace, userSlug), safeBranch);
        // 新布局缺失时回退老布局 .worktrees/{branch}, 兼容 P1 之前创建的 worktree
        Path legacyBase = resolveWithinBucket(workspace.resolve(WORKTREE_DIR), safeBranch);
        final Path worktreeBase = Files.isDirectory(userBase) ? userBase : legacyBase;
        if (!Files.isDirectory(worktreeBase)) {
            return;
        }

        LeafScan scan = classifyLeaves(worktreeBase);
        for (Path link : scan.links) {
            Files.delete(link);
        }
        for (Path wt : scan.realWorktrees) {
            Path originalRepo = workspace.resolve(worktreeBase.relativize(wt));
            if (Files.isDirectory(originalRepo) && Files.exists(originalRepo.resolve(GIT_DIR))) {
                // 删除前取该 worktree 实际检出的分支, 才能精确回收私有 ref(覆盖 fallback 默认分支)
                String checkedOutRef = currentBranchRef(wt);
                exec(originalRepo.toFile(), "git", "worktree", "remove", "--force", wt.toString());
                recyclePrivateRef(originalRepo.toFile(), checkedOutRef);
            }
        }

        deleteRecursive(worktreeBase);
    }

    /** worktree 当前检出的分支名 (git rev-parse --abbrev-ref HEAD); 失败/分离头返回 null。 */
    private String currentBranchRef(Path worktree) throws IOException, InterruptedException {
        ExecResult r = execCapture(worktree.toFile(), "git", "rev-parse", "--abbrev-ref", "HEAD");
        return r.exitCode == 0 ? r.output.trim() : null;
    }

    /**
     * 回收私有 ref, best-effort。仅删 {@code wt/} 前缀的私有分支, 绝不误删真实业务分支;
     * {@code git branch -D} 失败(如 fallback 仓为 symlink 无私有 ref)无害忽略。
     */
    private void recyclePrivateRef(File repoDir, String checkedOutRef)
            throws IOException, InterruptedException {
        if (checkedOutRef != null && checkedOutRef.startsWith(UserBranchRef.PREFIX)) {
            exec(repoDir, "git", "branch", "-D", checkedOutRef);
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

    private Map<String, Object> createWorktreeForRepo(RepoEntry entry, String branch,
                                                      Path worktreeBase, String userSlug)
            throws IOException, InterruptedException {

        File repoDir = entry.dir;
        Map<String, Object> repoResult = new HashMap<>(16);
        // Use relative path as the logical name — disambiguates repos that share
        // the same leaf name under different parent directories.
        repoResult.put("name", entry.relativePath.toString());

        boolean localExists = localBranchExists(repoDir, branch);
        boolean remoteExists = remoteBranchExists(repoDir, branch);

        String logicalBranch;
        boolean isFallback;
        if (localExists || remoteExists) {
            logicalBranch = branch;
            isFallback = false;
        } else {
            logicalBranch = defaultBranch(repoDir);
            isFallback = true;
        }
        repoResult.put("actualBranch", logicalBranch);

        Path repoWorktree = worktreeBase.resolve(entry.relativePath);
        if (Files.exists(repoWorktree, LinkOption.NOFOLLOW_LINKS)) {
            repoResult.put("created", true);
            repoResult.put("reason", "已存在");
            return repoResult;
        }
        Path parent = repoWorktree.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Fallback 仓(无业务分支): 有远端默认分支 → 也建私有 worktree 隔离, 否则多用户 symlink 到同一主仓
        // 检出会被 agent 跨用户写污染。仅"纯本地无远端"的退化测试库保留 symlink 复用(无可隔离的远端真相源)。
        if (isFallback && !hasConfiguredRemote(repoDir)) {
            createDirectoryLink(repoWorktree, repoDir.toPath());
            repoResult.put("created", true);
            repoResult.put("reason", "无此分支，复用默认分支路径");
            return repoResult;
        }

        return addPrivateWorktree(repoDir, repoWorktree, userSlug, logicalBranch,
                remoteExists, isFallback, repoResult);
    }

    /**
     * 为当前用户建私有 ref {@code wt/{slug}/{logical}} 的 worktree。共享对象库下同名逻辑分支被
     * 命名空间化, 多用户各持私有 ref, checkout 互不冲突。二次进入(ref 残留)时复用而非重复 -b。
     */
    private Map<String, Object> addPrivateWorktree(File repoDir, Path repoWorktree, String userSlug,
                                                   String logicalBranch, boolean remoteExists, boolean isFallback,
                                                   Map<String, Object> repoResult)
            throws IOException, InterruptedException {

        String privateRef = UserBranchRef.of(userSlug, logicalBranch).namespacedRef();
        String[] cmd;
        if (localBranchExists(repoDir, privateRef)) {
            String existingPrivate = findCheckoutPath(repoDir, privateRef);
            if (existingPrivate != null && Files.isDirectory(Paths.get(existingPrivate))) {
                createDirectoryLink(repoWorktree, Paths.get(existingPrivate));
                repoResult.put("created", true);
                repoResult.put("reason", "复用已检出路径");
                return repoResult;
            }
            if (existingPrivate != null) {
                pruneStaleWorktrees(repoDir);
            }
            cmd = new String[]{"git", "worktree", "add", repoWorktree.toString(), privateRef};
        } else {
            String startPoint = remoteExists
                    ? ORIGIN_REF_PREFIX + logicalBranch
                    : logicalBranch;
            cmd = new String[]{"git", "worktree", "add", "-b", privateRef,
                    repoWorktree.toString(), startPoint};
        }

        ExecResult er = execCapture(repoDir, cmd);
        if (er.exitCode != 0 && isStaleWorktreeError(er.output)) {
            pruneStaleWorktrees(repoDir);
            er = execCapture(repoDir, cmd);
        }
        repoResult.put("created", er.exitCode == 0);
        if (er.exitCode != 0) {
            String msg = er.output.trim();
            repoResult.put("reason", msg.isEmpty() ? "创建失败" : msg);
            return repoResult;
        }
        if (isFallback) {
            repoResult.put("reason", "无此分支，已回退到默认分支");
        }
        return repoResult;
    }

    private void pruneStaleWorktrees(File repoDir) throws IOException, InterruptedException {
        exec(repoDir, "git", "worktree", "prune");
    }

    private boolean isStaleWorktreeError(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("is already checked out")
                || lower.contains("is a missing but already registered worktree")
                || lower.contains("already registered worktree");
    }

    /** 当前分支是否配置了 upstream; 无 upstream 的本地分支 pull --ff-only 会失败, 应按良性跳过。 */
    private boolean hasUpstream(File repoDir) throws IOException, InterruptedException {
        return execCapture(repoDir, "git", "rev-parse", "--abbrev-ref",
                "--symbolic-full-name", "@{u}").exitCode == 0;
    }

    private boolean localBranchExists(File repoDir, String branch)
            throws IOException, InterruptedException {
        ExecResult r = execCapture(repoDir, "git", "show-ref", "--verify",
                "--quiet", "refs/heads/" + branch);
        return r.exitCode == 0;
    }

    private boolean remoteBranchExists(File repoDir, String branch)
            throws IOException, InterruptedException {
        ExecResult r = execCapture(repoDir, "git", "show-ref", "--verify",
                "--quiet", "refs/remotes/origin/" + branch);
        return r.exitCode == 0;
    }

    /**
     * Determine the repo's default branch. Prefer {@code origin/HEAD}; fall back
     * to the repo's current HEAD branch so single-repo test setups without a
     * remote still resolve to something sensible.
     */
    private String defaultBranch(File repoDir) throws IOException, InterruptedException {
        ExecResult r = execCapture(repoDir, "git", "symbolic-ref", "--short",
                "refs/remotes/origin/HEAD");
        if (r.exitCode == 0) {
            String out = r.output.trim();
            if (out.startsWith(ORIGIN_REF_PREFIX)) {
                return out.substring(ORIGIN_REF_PREFIX.length());
            }
        }
        ExecResult head = execCapture(repoDir, "git", "rev-parse",
                "--abbrev-ref", "HEAD");
        String cur = head.output.trim();
        return cur.isEmpty() || "HEAD".equals(cur) ? "master" : cur;
    }

    /**
     * Returns the path of the existing worktree that currently has {@code branch}
     * checked out, or {@code null} if none. Parses {@code git worktree list --porcelain}.
     */
    private String findCheckoutPath(File repoDir, String branch)
            throws IOException, InterruptedException {
        ExecResult r = execCapture(repoDir, "git", "worktree", "list", "--porcelain");
        if (r.exitCode != 0) {
            return null;
        }

        String targetRef = "refs/heads/" + branch;
        String currentPath = null;
        for (String line : r.output.split(LF)) {
            if (line.startsWith("worktree ")) {
                currentPath = line.substring("worktree ".length()).trim();
            } else if (line.startsWith("branch ")) {
                String ref = line.substring("branch ".length()).trim();
                if (ref.equals(targetRef) && currentPath != null) {
                    return currentPath;
                }
            } else if (line.isEmpty()) {
                currentPath = null;
            }
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Create a directory link: symlink on Unix, NTFS junction on Windows.
     * Junctions do not require administrator privileges.
     */
    private void createDirectoryLink(Path link, Path target)
            throws IOException, InterruptedException {
        if (isWindows()) {
            ExecResult r = execCapture(link.getParent().toFile(),
                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString());
            if (r.exitCode != 0) {
                throw new IOException("Failed to create junction: " + r.output.trim());
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    /**
     * Check whether a path is a directory link (symlink or NTFS junction).
     */
    private boolean isDirectoryLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        // Junction points are not detected by isSymbolicLink on Windows
        if (isWindows() && Files.isDirectory(path)) {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent == null) {
                    return false;
                }
                // 父目录规范化后拼回叶子名 = path 若为普通目录时应有的真实路径；
                // toRealPath 会跟随 junction，二者不同即说明 path 是 junction。
                // 不能直接拿 path.toAbsolutePath().normalize() 比较：toRealPath 会把
                // 8.3 短名（如 V33215~1）展开为长名而 normalize 不会，会令普通目录被误判为链接。
                Path expectedReal = parent.toRealPath().resolve(path.getFileName());
                return !path.toRealPath().equals(expectedReal);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private int execWithTimeout(File dir, int timeoutSeconds, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread outputDrainer = new Thread(() -> {
            try {
                consumeOutput(p);
            } catch (IOException ex) {
                // 超时强杀进程时 stdout 管道可能被关闭,fetch 输出本身不参与业务判断。
            }
        }, "worktree-git-output-drainer");
        outputDrainer.setDaemon(true);
        outputDrainer.start();

        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(1, TimeUnit.SECONDS);
            outputDrainer.join(1000L);
            return -1;
        }
        outputDrainer.join();
        return p.exitValue();
    }

    private int exec(File dir, String... cmd) throws IOException, InterruptedException {
        return execCapture(dir, cmd).exitCode;
    }

    private ExecResult execCapture(File dir, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = consumeOutput(p);
        int code = p.waitFor();
        ExecResult r = new ExecResult();
        r.exitCode = code;
        r.output = output;
        return r;
    }

    private String consumeOutput(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    private String safeBranchName(String branch) {
        String safe = branch.replaceAll("[^a-zA-Z0-9._-]", "-");
        // 防目录穿越: 整段为 "." / ".." 会让 resolve 跳出用户桶, 直接拒绝(分隔符已被替换为 -, 故只需挡这两种)
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Illegal branch name: " + branch);
        }
        return safe;
    }

    /**
     * 在桶目录下解析分支目录并断言不逃逸出桶(纵深防御, 即便 safeBranchName 有漏网也兜住)。
     *
     * @throws IllegalArgumentException 解析结果跳出桶时
     */
    private Path resolveWithinBucket(Path bucket, String safeBranch) {
        Path bucketRoot = bucket.normalize();
        Path resolved = bucketRoot.resolve(safeBranch).normalize();
        if (!resolved.startsWith(bucketRoot)) {
            throw new IllegalArgumentException("Branch path escapes bucket: " + safeBranch);
        }
        return resolved;
    }

    private static final class ExecResult {
        int exitCode;
        String output;
    }

    private static final class RepoEntry {
        final File dir;
        final Path relativePath;

        RepoEntry(File dir, Path relativePath) {
            this.dir = dir;
            this.relativePath = relativePath;
        }
    }
}
