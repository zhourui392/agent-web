package com.example.agentweb.app;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Manages git worktrees for branch-level isolation across a workspace
 * containing multiple independent git repositories.
 */
@Service
public class WorktreeService {

    private static final String WORKTREE_DIR = ".worktrees";

    /**
     * Switch a workspace to a given branch by creating worktrees for every
     * git repo under {@code workspacePath} that contains the branch.
     *
     * @return result map with worktreePath, branch, and per-repo status
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

        List<Map<String, Object>> repos = new ArrayList<>();

        File[] children = workspace.toFile().listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) {
                if (!child.isDirectory() || child.getName().startsWith(".")) {
                    continue;
                }
                if (!new File(child, ".git").exists()) {
                    continue;
                }
                repos.add(processRepo(child, branch, worktreeBase));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("worktreePath", worktreeBase.toString());
        result.put("branch", branch);
        result.put("repos", repos);
        return result;
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

        File[] repos = worktreeBase.toFile().listFiles(File::isDirectory);
        if (repos != null) {
            for (File repoWorktree : repos) {
                File originalRepo = workspace.resolve(repoWorktree.getName()).toFile();
                if (originalRepo.isDirectory() && new File(originalRepo, ".git").exists()) {
                    exec(originalRepo, "git", "worktree", "remove", "--force",
                            repoWorktree.getAbsolutePath());
                }
            }
        }

        deleteRecursive(worktreeBase);
    }

    // ---- internal helpers ----

    private Map<String, Object> processRepo(File repoDir, String branch, Path worktreeBase)
            throws IOException, InterruptedException {

        Map<String, Object> repoResult = new HashMap<>();
        repoResult.put("name", repoDir.getName());

        // fetch latest (best-effort, may fail without network)
        exec(repoDir, "git", "fetch", "--all", "--prune");

        if (!branchExists(repoDir, branch)) {
            repoResult.put("created", false);
            repoResult.put("reason", "分支不存在");
            return repoResult;
        }

        Path repoWorktree = worktreeBase.resolve(repoDir.getName());
        if (Files.isDirectory(repoWorktree)) {
            repoResult.put("created", true);
            repoResult.put("reason", "已存在");
            return repoResult;
        }

        // Try creating worktree with the branch name directly.
        // Git will auto-create a tracking local branch if only remote exists.
        int code = exec(repoDir, "git", "worktree", "add",
                repoWorktree.toString(), branch);

        if (code != 0) {
            // Branch might already be checked out; fall back to detached HEAD
            code = exec(repoDir, "git", "worktree", "add", "--detach",
                    repoWorktree.toString(), "origin/" + branch);
        }

        repoResult.put("created", code == 0);
        if (code != 0) {
            repoResult.put("reason", "创建失败");
        }
        return repoResult;
    }

    private boolean branchExists(File repoDir, String branch)
            throws IOException, InterruptedException {
        // local branch
        String local = execOutput(repoDir, "git", "branch", "--list", branch);
        if (!local.trim().isEmpty()) {
            return true;
        }
        // remote branch
        String remote = execOutput(repoDir, "git", "branch", "-r", "--list",
                "origin/" + branch);
        return !remote.trim().isEmpty();
    }

    private int exec(File dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        consumeOutput(p);
        return p.waitFor();
    }

    private String execOutput(File dir, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = consumeOutput(p);
        p.waitFor();
        return output;
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
}
