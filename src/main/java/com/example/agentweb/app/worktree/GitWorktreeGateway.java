package com.example.agentweb.app.worktree;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * git 子进程语义操作端口。隔离 worktree 编排(app 层)与 git 命令方言 / 进程执行(infra 层)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface GitWorktreeGateway {

    /**
     * 对若干仓库并发执行 {@code git fetch --all --prune}(best-effort, 超时强杀)。
     *
     * @param repoDir        仓库目录
     * @param timeoutSeconds 超时秒数
     * @return 退出码, 超时返回 -1
     */
    int fetchAll(File repoDir, int timeoutSeconds) throws IOException, InterruptedException;

    /**
     * 当前分支是否配置了 upstream。
     *
     * @return 无 upstream 的本地分支 pull --ff-only 会失败, 调用方应按良性跳过
     */
    boolean hasUpstream(File repoDir) throws IOException, InterruptedException;

    /** @return 当前 HEAD commit hash */
    String headCommit(File repoDir) throws IOException, InterruptedException;

    /** 执行 {@code git pull --ff-only}。 */
    GitExecResult pullFastForwardOnly(File repoDir) throws IOException, InterruptedException;

    boolean localBranchExists(File repoDir, String branch) throws IOException, InterruptedException;

    boolean remoteBranchExists(File repoDir, String branch) throws IOException, InterruptedException;

    /**
     * 解析仓库默认分支: 优先 {@code origin/HEAD}, 回退当前 HEAD 分支。
     */
    String defaultBranch(File repoDir) throws IOException, InterruptedException;

    /**
     * 返回当前检出 {@code branch} 的已有 worktree 路径, 无则返回 null。
     */
    String findCheckoutPath(File repoDir, String branch) throws IOException, InterruptedException;

    /**
     * 建 worktree。
     *
     * @param privateRef  要检出的(私有)分支名
     * @param startPoint  非 null 时以 {@code -b privateRef} 从 startPoint 新建分支; null 时直接检出已有 ref
     */
    GitExecResult addWorktree(File repoDir, Path worktreePath, String privateRef, String startPoint)
            throws IOException, InterruptedException;

    /**
     * 判断 git 输出是否为"陈旧 worktree 注册"错误(可通过 prune 自愈)。
     */
    boolean isStaleWorktreeError(String output);

    /** {@code git worktree prune}。 */
    void pruneWorktrees(File repoDir) throws IOException, InterruptedException;

    /** {@code git worktree remove --force}。 */
    void removeWorktree(File repoDir, Path worktreePath) throws IOException, InterruptedException;

    /** {@code git branch -D}(调用方负责只传私有 ref)。 */
    void deleteBranch(File repoDir, String ref) throws IOException, InterruptedException;

    /**
     * worktree 当前检出的分支名, 失败 / 分离头返回 null。
     */
    String currentBranchRef(Path worktree) throws IOException, InterruptedException;
}
