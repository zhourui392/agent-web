package com.example.agentweb.app.workbench.port;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Workbench 专属 worktree 创建与清理端口。
 *
 * <p>隔离 Workbench 创建链路对 git 子进程的依赖。实现可复用
 * {@link com.example.agentweb.app.worktree.GitWorktreeGateway} 的底层操作。</p>
 *
 * @author alex
 * @since 2026-08-07
 */
public interface WorkbenchWorktreeGateway {

    /**
     * 为 primary 仓库创建 linked worktree，从当前 HEAD 新建分支。
     *
     * @param primaryRepositoryRoot 主仓库真实根路径
     * @param worktreePath           worktree 目标路径
     * @param branch                 要新建并检出的分支名
     * @return worktree 实际检出路径（归一化绝对路径）
     */
    String createWorktree(String primaryRepositoryRoot, Path worktreePath, String branch)
            throws IOException, InterruptedException;

    /**
     * 移除 worktree 并删除对应分支。
     *
     * @param primaryRepositoryRoot 主仓库真实根路径
     * @param worktreePath           worktree 路径
     * @param branch                 要删除的分支名
     */
    void removeWorktree(String primaryRepositoryRoot, Path worktreePath, String branch)
            throws IOException, InterruptedException;
}
