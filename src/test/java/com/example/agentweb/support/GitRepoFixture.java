package com.example.agentweb.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 测试用真实 git 裸仓 fixture：在临时目录建裸仓当 origin，经内部工作 clone
 * 提交并推回，builder 风格链式构造仓库内容。
 *
 * <p>默认分支显式固定为 main（init -b main），屏蔽不同 git 版本
 * init.defaultBranch 差异。裸仓绝对路径可直接当 repoUrl（git 支持本地路径）。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public final class GitRepoFixture {

    private static final String DEFAULT_BRANCH = "main";
    private static final int GIT_TIMEOUT_SECONDS = 30;

    private final Path bareDir;
    private final Path workDir;

    private GitRepoFixture(Path bareDir, Path workDir) {
        this.bareDir = bareDir;
        this.workDir = workDir;
    }

    /** 在 dir 建裸仓（默认分支 main），并在旁路目录准备工作 clone 用于造提交。 */
    public static GitRepoFixture createBare(Path dir) {
        try {
            Files.createDirectories(dir);
            Path work = dir.getParent().resolve(dir.getFileName() + "-work");
            Files.createDirectories(work);
            git(dir, "init", "--bare", "-b", DEFAULT_BRANCH, ".");
            git(work, "init", "-b", DEFAULT_BRANCH, ".");
            git(work, "config", "user.name", "fixture");
            git(work, "config", "user.email", "fixture@test.local");
            git(work, "config", "commit.gpgsign", "false");
            git(work, "remote", "add", "origin", dir.toAbsolutePath().toString());
            return new GitRepoFixture(dir, work);
        } catch (IOException e) {
            throw new IllegalStateException("创建 git fixture 失败: " + dir, e);
        }
    }

    /** 在当前分支写文件并提交，随后推回裸仓。 */
    public GitRepoFixture withCommit(String file, String content, String message) {
        try {
            Path target = workDir.resolve(file);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            git(workDir, "add", "-A");
            git(workDir, "commit", "-m", message);
            git(workDir, "push", "origin", currentBranch());
            return this;
        } catch (IOException e) {
            throw new IllegalStateException("fixture 提交失败: " + file, e);
        }
    }

    /** 从当前 HEAD 切出新分支并推回裸仓，后续 withCommit 落在该分支。 */
    public GitRepoFixture withBranch(String name) {
        git(workDir, "checkout", "-b", name);
        git(workDir, "push", "origin", name);
        return this;
    }

    /** 裸仓绝对路径，git 支持本地路径当 URL。 */
    public String url() {
        return bareDir.toAbsolutePath().toString();
    }

    /** 裸仓 HEAD（默认分支）当前提交。 */
    public String headCommit() {
        return git(bareDir, "rev-parse", "HEAD").trim();
    }

    private String currentBranch() {
        return git(workDir, "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /** 供测试断言复用的 git 执行入口：非零退出码抛异常，返回合并输出。 */
    public static String git(Path dir, String... args) {
        GitOutcome outcome = tryGit(dir, args);
        if (outcome.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " 失败 exit=" + outcome.exitCode() + " output=" + outcome.output());
        }
        return outcome.output();
    }

    /** 供测试按退出码断言的宽松入口（如 show-ref / config --get）。 */
    public static GitOutcome tryGit(Path dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readAll(process);
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git 命令超时: " + command);
            }
            return new GitOutcome(process.exitValue(), output);
        } catch (IOException e) {
            throw new IllegalStateException("git 命令执行失败: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git 命令被中断: " + command, e);
        }
    }

    private static String readAll(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** git 退出码 + 合并输出。 */
    public record GitOutcome(int exitCode, String output) {
    }
}
