package com.example.agentweb.infra.workspace;

import com.example.agentweb.adapter.workspace.ProvisionRequest;
import com.example.agentweb.adapter.workspace.ProvisionedWorkspace;
import com.example.agentweb.adapter.workspace.ReleaseRequest;
import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.domain.workspace.DirtyReport;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WorkspaceProvisioner 的 git worktree 实现（detailed-design §2.4）。
 *
 * <p>目录布局：{@code {root}/mirrors/<repo-slug>.git}（bare 共享对象库）
 * + {@code {root}/worktrees/<requirementId>}（每需求一个 worktree）。</p>
 *
 * <p><b>红线：禁用 {@code git clone --mirror}</b> —— mirror 语义下 push 会按镜像
 * 覆盖远端分支、fetch --prune 会修剪未推送的本地 req/* 分支。此处用
 * {@code clone --bare} + 显式 refspec {@code +refs/heads/*:refs/remotes/origin/*}：
 * 远端分支落 refs/remotes/，本地 req/* 落 refs/heads/，prune 只影响前者。</p>
 *
 * <p>并发：同一 mirror 上 fetch / worktree add / remove 按 repo-slug 互斥，
 * 不同仓互不阻塞；detectDirty 只读不加锁。Bean 装配由外部负责，本类不加 Spring 注解。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class GitWorktreeProvisioner implements WorkspaceProvisioner {

    private static final String MIRRORS_DIR = "mirrors";
    private static final String WORKTREES_DIR = "worktrees";
    private static final String ORIGIN = "origin";
    private static final String GIT_DIR_SUFFIX = ".git";
    private static final String BARE_FETCH_REFSPEC = "+refs/heads/*:refs/remotes/origin/*";
    private static final long CLONE_FETCH_TIMEOUT_SECONDS = 120;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int OUTPUT_SUMMARY_MAX = 500;

    private final Path root;
    private final ConcurrentHashMap<String, ReentrantLock> mirrorLocks = new ConcurrentHashMap<>();

    public GitWorktreeProvisioner(String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    @Override
    public ProvisionedWorkspace provision(ProvisionRequest request) {
        String slug = repoSlug(request.getRepoUrl());
        ReentrantLock lock = lockFor(slug);
        lock.lock();
        try {
            Path mirror = ensureMirror(request.getRepoUrl(), slug);
            String baseBranch = resolveBaseBranch(mirror, request.getBaseRef());
            Path worktree = ensureWorktree(mirror, request, baseBranch);
            String baseCommit = runOrThrow(worktree.toFile(), DEFAULT_TIMEOUT_SECONDS,
                    "rev-parse", "HEAD").trim();
            log.info("workspace-provisioned requirementId={} slug={} branch={} worktree={}",
                    request.getRequirementId(), slug, request.getBranch(), worktree);
            return new ProvisionedWorkspace(mirror.toString(), worktree.toString(), baseCommit);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(ReleaseRequest request) {
        ReentrantLock lock = lockFor(lockKeyOfMirror(request.getMirrorPath()));
        lock.lock();
        try {
            File mirrorDir = new File(request.getMirrorPath());
            runOrThrow(mirrorDir, DEFAULT_TIMEOUT_SECONDS, "worktree", "prune");
            removeWorktreeIfPresent(mirrorDir, request);
            removeBranchIfRequested(mirrorDir, request);
            log.info("workspace-released worktree={} branch={} force={} removeBranch={}",
                    request.getWorktreePath(), request.getBranch(),
                    request.isForce(), request.isRemoveBranch());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DirtyReport detectDirty(String worktreePath) {
        Path worktree = Paths.get(worktreePath);
        if (!Files.isDirectory(worktree)) {
            return DirtyReport.clean();
        }
        List<String> uncommitted = parseStatusFiles(
                runOrThrow(worktree.toFile(), DEFAULT_TIMEOUT_SECONDS, "status", "--porcelain"));
        int unpushed = countUnpushedCommits(worktree);
        return new DirtyReport(uncommitted, unpushed);
    }

    /** mirror 不存在则 bare clone + 写 refspec；无论新旧一律 fetch --prune 刷新 refs/remotes/。 */
    private Path ensureMirror(String repoUrl, String slug) {
        Path mirror = root.resolve(MIRRORS_DIR).resolve(slug + GIT_DIR_SUFFIX);
        if (!Files.isDirectory(mirror)) {
            cloneBareMirror(repoUrl, mirror);
        }
        assertNoMirrorSemantics(mirror);
        log.info("mirror-fetch slug={}", slug);
        runOrThrow(mirror.toFile(), CLONE_FETCH_TIMEOUT_SECONDS, "fetch", "--prune", ORIGIN);
        return mirror;
    }

    /**
     * 红线落点：clone --bare 而非 --mirror。bare clone 只填 refs/heads/ 且不带 fetch refspec，
     * 故显式写 refspec 让远端分支落 refs/remotes/、本地 req/* 独占 refs/heads/，
     * fetch --prune 只修剪前者；core.longpaths 兜底 Windows 长路径。
     */
    private void cloneBareMirror(String repoUrl, Path mirror) {
        try {
            Files.createDirectories(mirror.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("创建 mirrors 目录失败: " + mirror.getParent(), e);
        }
        log.info("mirror-clone target={}", mirror);
        runOrThrow(mirror.getParent().toFile(), CLONE_FETCH_TIMEOUT_SECONDS,
                "clone", "--bare", repoUrl, mirror.toString());
        runOrThrow(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS,
                "config", "remote.origin.fetch", BARE_FETCH_REFSPEC);
        runOrThrow(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS, "config", "core.longpaths", "true");
    }

    /** mirror 语义污染断言：remote.origin.mirror=true 时 push/fetch 都按镜像走，直接拒绝。 */
    private void assertNoMirrorSemantics(Path mirror) {
        GitResult result = run(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS,
                "config", "--get", "remote.origin.mirror");
        if (result.exitCode() == 0 && "true".equalsIgnoreCase(result.output().trim())) {
            throw new IllegalStateException(
                    "mirror 语义污染（remote.origin.mirror=true），拒绝使用: " + mirror);
        }
    }

    /** baseRef 非空直接用；为空取 mirror 的 HEAD 指向（bare clone 的 HEAD 即远端默认分支）。 */
    private String resolveBaseBranch(Path mirror, String baseRef) {
        if (baseRef != null && !baseRef.isBlank()) {
            return baseRef;
        }
        return runOrThrow(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS,
                "symbolic-ref", "--short", "HEAD").trim();
    }

    /** 分支不存在→ -b 新建于 origin/&lt;base&gt;；已存在且 worktree 有效→幂等返回，否则重挂。 */
    private Path ensureWorktree(Path mirror, ProvisionRequest request, String baseBranch) {
        Path worktree = worktreePathOf(request.getRequirementId());
        boolean branchExists = branchExists(mirror, request.getBranch());
        if (branchExists && isValidWorktree(worktree)) {
            return worktree;
        }
        try {
            Files.createDirectories(worktree.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("创建 worktrees 目录失败: " + worktree.getParent(), e);
        }
        if (branchExists) {
            runOrThrow(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS, "worktree", "prune");
            runOrThrow(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS,
                    "worktree", "add", worktree.toString(), request.getBranch());
        } else {
            runOrThrow(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS, "worktree", "add",
                    "-b", request.getBranch(), worktree.toString(), ORIGIN + "/" + baseBranch);
        }
        return worktree;
    }

    /** release 前置已 worktree prune，路径不存在即幂等成功。 */
    private void removeWorktreeIfPresent(File mirrorDir, ReleaseRequest request) {
        Path worktree = Paths.get(request.getWorktreePath());
        if (!Files.exists(worktree)) {
            return;
        }
        List<String> args = new ArrayList<>(List.of("worktree", "remove"));
        if (request.isForce()) {
            args.add("--force");
        }
        args.add(worktree.toString());
        runOrThrow(mirrorDir, DEFAULT_TIMEOUT_SECONDS, args.toArray(String[]::new));
    }

    private void removeBranchIfRequested(File mirrorDir, ReleaseRequest request) {
        if (!request.isRemoveBranch()) {
            return;
        }
        if (branchExists(mirrorDir.toPath(), request.getBranch())) {
            runOrThrow(mirrorDir, DEFAULT_TIMEOUT_SECONDS, "branch", "-D", request.getBranch());
        }
    }

    /**
     * worktree 分支无 upstream，@{upstream} 不可用；以 mirror 默认分支
     * （symbolic-ref HEAD，与 provision 同一解析函数）为基准数本地未交付提交。
     */
    private int countUnpushedCommits(Path worktree) {
        Path mirror = resolveMirrorOf(worktree);
        String baseBranch = resolveBaseBranch(mirror, null);
        String count = runOrThrow(worktree.toFile(), DEFAULT_TIMEOUT_SECONDS,
                "rev-list", "--count", ORIGIN + "/" + baseBranch + "..HEAD").trim();
        return Integer.parseInt(count);
    }

    /** 经 git-common-dir 从 worktree 反查 mirror（相对路径按 worktree 基准归一化）。 */
    private Path resolveMirrorOf(Path worktree) {
        String commonDir = runOrThrow(worktree.toFile(), DEFAULT_TIMEOUT_SECONDS,
                "rev-parse", "--git-common-dir").trim();
        Path common = Paths.get(commonDir);
        if (!common.isAbsolute()) {
            common = worktree.resolve(common).normalize();
        }
        return common;
    }

    /** porcelain 每行前 3 列是状态位 + 空格，第 4 列起为文件路径。 */
    private List<String> parseStatusFiles(String statusOutput) {
        List<String> files = new ArrayList<>();
        for (String line : statusOutput.split("\n")) {
            if (line.length() >= 4) {
                files.add(line.substring(3).trim());
            }
        }
        return files;
    }

    private boolean branchExists(Path mirror, String branch) {
        return run(mirror.toFile(), DEFAULT_TIMEOUT_SECONDS,
                "show-ref", "--verify", "--quiet", "refs/heads/" + branch).exitCode() == 0;
    }

    private boolean isValidWorktree(Path worktree) {
        return Files.isDirectory(worktree) && Files.exists(worktree.resolve(GIT_DIR_SUFFIX));
    }

    private Path worktreePathOf(String requirementId) {
        Path base = root.resolve(WORKTREES_DIR);
        Path resolved = base.resolve(requirementId).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("requirementId 越出 worktrees 根目录: " + requirementId);
        }
        return resolved;
    }

    /** 去 .git 后缀取最后路径段，非法字符换 '-'；兼容 http(s) / scp-like / 本地路径。 */
    private String repoSlug(String repoUrl) {
        String trimmed = repoUrl.trim();
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(GIT_DIR_SUFFIX)) {
            trimmed = trimmed.substring(0, trimmed.length() - GIT_DIR_SUFFIX.length());
        }
        int cut = Math.max(trimmed.lastIndexOf('/'),
                Math.max(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf(':')));
        String segment = cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
        String slug = segment.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (slug.isBlank() || ".".equals(slug) || "..".equals(slug)) {
            throw new IllegalArgumentException("无法从 repoUrl 提取 repo-slug: " + sanitize(repoUrl));
        }
        return slug;
    }

    /** release 侧锁键：mirror 目录名去 .git 即 provision 侧的 repo-slug，两侧共用一把锁。 */
    private String lockKeyOfMirror(String mirrorPath) {
        String name = Paths.get(mirrorPath).getFileName().toString();
        return name.endsWith(GIT_DIR_SUFFIX)
                ? name.substring(0, name.length() - GIT_DIR_SUFFIX.length())
                : name;
    }

    private ReentrantLock lockFor(String slug) {
        return mirrorLocks.computeIfAbsent(slug, key -> new ReentrantLock());
    }

    private String runOrThrow(File dir, long timeoutSeconds, String... args) {
        GitResult result = run(dir, timeoutSeconds, args);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git 命令失败 exit=" + result.exitCode()
                    + " cmd=git " + sanitize(String.join(" ", args))
                    + " output=" + sanitize(summarize(result.output())));
        }
        return result.output();
    }

    private GitResult run(File dir, long timeoutSeconds, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        try {
            return execute(dir, command, timeoutSeconds);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "git 进程启动失败 cmd=" + sanitize(String.join(" ", command)), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "git 进程被中断 cmd=" + sanitize(String.join(" ", command)), e);
        }
    }

    private GitResult execute(File dir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        Thread drainer = startDrainer(process, output);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            drainer.join(1000L);
            throw new IllegalStateException("git 命令超时(" + timeoutSeconds + "s) cmd="
                    + sanitize(String.join(" ", command)));
        }
        drainer.join(5000L);
        return new GitResult(process.exitValue(), output.toString());
    }

    private Thread startDrainer(Process process, StringBuilder output) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException e) {
                // 超时强杀进程时管道被关闭属预期，该路径的输出不参与业务判断
            }
        }, "git-provisioner-drainer");
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    /** URL 内嵌凭证统一打码，日志与异常里不得出现明文。 */
    private String sanitize(String text) {
        return text == null ? "" : text.replaceAll("://[^/@\\s]+@", "://***@");
    }

    private String summarize(String output) {
        String trimmed = output == null ? "" : output.trim();
        return trimmed.length() <= OUTPUT_SUMMARY_MAX
                ? trimmed
                : trimmed.substring(0, OUTPUT_SUMMARY_MAX) + "...";
    }

    /** git 退出码 + 合并输出。 */
    private record GitResult(int exitCode, String output) {
    }
}
