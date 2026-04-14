package com.example.agentweb.app;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages git worktrees for branch-level isolation across a workspace
 * containing multiple independent git repositories.
 */
@Service
public class WorktreeService {

    private static final String WORKTREE_DIR = ".worktrees";
    private static final int FETCH_TIMEOUT_SECONDS = 30;
    private static final int THREAD_POOL_SIZE = 8;

    /**
     * Switch a workspace to a given branch by creating worktrees for every
     * git repo under {@code workspacePath}. Repos that have the target branch
     * get a real worktree on it; repos that don't fall back to the default
     * branch (origin/HEAD, else current HEAD) via a symlink to the main repo.
     */
    public Map<String, Object> switchBranch(String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = Paths.get(workspacePath).normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("Workspace not found: " + workspacePath);
        }

        String safeBranch = safeBranchName(branch);
        Path worktreeBase = workspace.resolve(WORKTREE_DIR).resolve(safeBranch);
        Files.createDirectories(worktreeBase);

        List<File> gitRepos = collectGitRepos(workspace);

        parallelFetch(gitRepos);

        List<Map<String, Object>> repos = new ArrayList<>();
        for (File repo : gitRepos) {
            repos.add(createWorktreeForRepo(repo, branch, worktreeBase));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("worktreePath", worktreeBase.toString());
        result.put("branch", branch);
        result.put("repos", repos);
        return result;
    }

    private List<File> collectGitRepos(Path workspace) {
        List<File> gitRepos = new ArrayList<>();
        File[] children = workspace.toFile().listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) {
                if (!child.isDirectory() || child.getName().startsWith(".")) {
                    continue;
                }
                if (new File(child, ".git").exists()) {
                    gitRepos.add(child);
                }
            }
        }
        return gitRepos;
    }

    private void parallelFetch(List<File> repos) throws InterruptedException {
        if (repos.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(THREAD_POOL_SIZE, repos.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (File repo : repos) {
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

    /**
     * List all worktree branch directories under the workspace.
     */
    public List<Map<String, Object>> listWorktrees(String workspacePath) {
        Path worktreeBase = Paths.get(workspacePath).normalize().resolve(WORKTREE_DIR);
        List<Map<String, Object>> result = new ArrayList<>();

        if (!Files.isDirectory(worktreeBase)) {
            return result;
        }

        File[] branches = worktreeBase.toFile().listFiles();
        if (branches != null) {
            Arrays.sort(branches);
            for (File branchDir : branches) {
                if (!branchDir.isDirectory()) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("branch", branchDir.getName());
                item.put("path", branchDir.getAbsolutePath());
                File[] repos = branchDir.listFiles(File::isDirectory);
                item.put("repoCount", repos != null ? repos.length : 0);
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Remove all worktrees for a branch and clean up the directory.
     */
    public void removeWorktree(String workspacePath, String branch)
            throws IOException, InterruptedException {

        Path workspace = Paths.get(workspacePath).normalize();
        String safeBranch = safeBranchName(branch);
        Path worktreeBase = workspace.resolve(WORKTREE_DIR).resolve(safeBranch);

        if (!Files.isDirectory(worktreeBase)) {
            return;
        }

        File[] entries = worktreeBase.toFile().listFiles();
        if (entries != null) {
            for (File entry : entries) {
                Path entryPath = entry.toPath();
                // Symlinks point back to the main repo (fallback path); just unlink.
                if (Files.isSymbolicLink(entryPath)) {
                    Files.delete(entryPath);
                    continue;
                }
                File originalRepo = workspace.resolve(entry.getName()).toFile();
                if (originalRepo.isDirectory() && new File(originalRepo, ".git").exists()) {
                    exec(originalRepo, "git", "worktree", "remove", "--force",
                            entry.getAbsolutePath());
                }
            }
        }

        deleteRecursive(worktreeBase);
    }

    // ---- internal helpers ----

    private Map<String, Object> createWorktreeForRepo(File repoDir, String branch, Path worktreeBase)
            throws IOException, InterruptedException {

        Map<String, Object> repoResult = new HashMap<>();
        repoResult.put("name", repoDir.getName());

        boolean localExists = localBranchExists(repoDir, branch);
        boolean remoteExists = remoteBranchExists(repoDir, branch);

        String actualBranch;
        boolean isFallback;
        if (localExists || remoteExists) {
            actualBranch = branch;
            isFallback = false;
        } else {
            actualBranch = defaultBranch(repoDir);
            isFallback = true;
        }
        repoResult.put("actualBranch", actualBranch);

        Path repoWorktree = worktreeBase.resolve(repoDir.getName());
        if (Files.exists(repoWorktree, LinkOption.NOFOLLOW_LINKS)) {
            repoResult.put("created", true);
            repoResult.put("reason", "已存在");
            return repoResult;
        }

        // If the target branch is already checked out somewhere (including the main
        // repo), git refuses to add another worktree for it. Symlink to that path
        // instead — this handles both the "repo has no such branch, fallback to
        // master currently checked out in main repo" case and the "user picks the
        // branch already in use" case, and avoids detached-HEAD fallbacks.
        String existingCheckout = findCheckoutPath(repoDir, actualBranch);
        if (existingCheckout != null) {
            Files.createSymbolicLink(repoWorktree, Paths.get(existingCheckout));
            repoResult.put("created", true);
            repoResult.put("reason", isFallback ? "无此分支，复用默认分支路径" : "复用已检出路径");
            return repoResult;
        }

        String[] cmd;
        if (localBranchExists(repoDir, actualBranch)) {
            cmd = new String[]{"git", "worktree", "add",
                    repoWorktree.toString(), actualBranch};
        } else if (remoteBranchExists(repoDir, actualBranch)) {
            // Create a new local tracking branch from origin/<branch>.
            cmd = new String[]{"git", "worktree", "add", "-b", actualBranch,
                    repoWorktree.toString(), "origin/" + actualBranch};
        } else {
            repoResult.put("created", false);
            repoResult.put("reason", "分支不存在");
            return repoResult;
        }

        ExecResult er = execCapture(repoDir, cmd);
        repoResult.put("created", er.exitCode == 0);
        if (er.exitCode != 0) {
            String msg = er.output.trim();
            repoResult.put("reason", msg.isEmpty() ? "创建失败" : msg);
        } else if (isFallback) {
            repoResult.put("reason", "无此分支，已回退到默认分支");
        }
        return repoResult;
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
            if (out.startsWith("origin/")) {
                return out.substring("origin/".length());
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
        if (r.exitCode != 0) return null;

        String targetRef = "refs/heads/" + branch;
        String currentPath = null;
        for (String line : r.output.split("\n")) {
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

    private int execWithTimeout(File dir, int timeoutSeconds, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        consumeOutput(p);
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private int exec(File dir, String... cmd) throws IOException, InterruptedException {
        return execCapture(dir, cmd).exitCode;
    }

    private String execOutput(File dir, String... cmd)
            throws IOException, InterruptedException {
        return execCapture(dir, cmd).output;
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
        return branch.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private static final class ExecResult {
        int exitCode;
        String output;
    }
}
