package com.example.agentweb.infra.git;

import com.example.agentweb.app.worktree.GitExecResult;
import com.example.agentweb.app.worktree.GitWorktreeGateway;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@link GitWorktreeGateway} 的 git CLI 子进程实现。
 *
 * <p>承载全部 git 命令方言: 参数拼装、输出解析({@code worktree list --porcelain})、
 * 错误文案识别(陈旧注册)、超时强杀。app 层只见语义化方法。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
public class ProcessGitWorktreeGateway implements GitWorktreeGateway {

    private static final String GIT_DIR = ".git";
    private static final String ORIGIN_REF_PREFIX = "origin/";
    private static final String LF = "\n";

    @Override
    public int fetchAll(File repoDir, int timeoutSeconds) throws IOException, InterruptedException {
        return execWithTimeout(repoDir, timeoutSeconds, "git", "fetch", "--all", "--prune");
    }

    @Override
    public boolean hasUpstream(File repoDir) throws IOException, InterruptedException {
        return execCapture(repoDir, "git", "rev-parse", "--abbrev-ref",
                "--symbolic-full-name", "@{u}").isSuccess();
    }

    @Override
    public String headCommit(File repoDir) throws IOException, InterruptedException {
        return execCapture(repoDir, "git", "rev-parse", "HEAD").output().trim();
    }

    @Override
    public GitExecResult pullFastForwardOnly(File repoDir) throws IOException, InterruptedException {
        return execCapture(repoDir, "git", "pull", "--ff-only");
    }

    @Override
    public boolean localBranchExists(File repoDir, String branch) throws IOException, InterruptedException {
        return execCapture(repoDir, "git", "show-ref", "--verify",
                "--quiet", "refs/heads/" + branch).isSuccess();
    }

    @Override
    public boolean remoteBranchExists(File repoDir, String branch) throws IOException, InterruptedException {
        return execCapture(repoDir, "git", "show-ref", "--verify",
                "--quiet", "refs/remotes/origin/" + branch).isSuccess();
    }

    @Override
    public String defaultBranch(File repoDir) throws IOException, InterruptedException {
        GitExecResult r = execCapture(repoDir, "git", "symbolic-ref", "--short",
                "refs/remotes/origin/HEAD");
        if (r.isSuccess()) {
            String out = r.output().trim();
            if (out.startsWith(ORIGIN_REF_PREFIX)) {
                return out.substring(ORIGIN_REF_PREFIX.length());
            }
        }
        GitExecResult head = execCapture(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
        String cur = head.output().trim();
        return cur.isEmpty() || "HEAD".equals(cur) ? "master" : cur;
    }

    @Override
    public String findCheckoutPath(File repoDir, String branch) throws IOException, InterruptedException {
        GitExecResult r = execCapture(repoDir, "git", "worktree", "list", "--porcelain");
        if (!r.isSuccess()) {
            return null;
        }
        String targetRef = "refs/heads/" + branch;
        String currentPath = null;
        for (String line : r.output().split(LF)) {
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

    @Override
    public GitExecResult addWorktree(File repoDir, Path worktreePath, String privateRef, String startPoint)
            throws IOException, InterruptedException {
        String[] cmd = startPoint == null
                ? new String[]{"git", "worktree", "add", worktreePath.toString(), privateRef}
                : new String[]{"git", "worktree", "add", "-b", privateRef,
                        worktreePath.toString(), startPoint};
        return execCapture(repoDir, cmd);
    }

    @Override
    public boolean isStaleWorktreeError(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("is already checked out")
                || lower.contains("is a missing but already registered worktree")
                || lower.contains("already registered worktree");
    }

    @Override
    public void pruneWorktrees(File repoDir) throws IOException, InterruptedException {
        execCapture(repoDir, "git", "worktree", "prune");
    }

    @Override
    public void removeWorktree(File repoDir, Path worktreePath) throws IOException, InterruptedException {
        execCapture(repoDir, "git", "worktree", "remove", "--force", worktreePath.toString());
    }

    @Override
    public void deleteBranch(File repoDir, String ref) throws IOException, InterruptedException {
        execCapture(repoDir, "git", "branch", "-D", ref);
    }

    @Override
    public String currentBranchRef(Path worktree) throws IOException, InterruptedException {
        GitExecResult r = execCapture(worktree.toFile(), "git", "rev-parse", "--abbrev-ref", "HEAD");
        return r.isSuccess() ? r.output().trim() : null;
    }

    // ---- 进程执行原语 ----

    /**
     * 带超时执行命令, 超时强杀并返回 -1。输出由守护线程排干, 防止管道缓冲区写满死锁。
     */
    public int execWithTimeout(File dir, int timeoutSeconds, String... cmd)
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

    private GitExecResult execCapture(File dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = consumeOutput(p);
        int code = p.waitFor();
        return new GitExecResult(code, output);
    }

    private String consumeOutput(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append(LF);
            }
        }
        return sb.toString();
    }
}
