package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.port.WorkbenchWorktreeGateway;
import com.example.agentweb.app.worktree.GitExecResult;
import com.example.agentweb.app.worktree.GitWorktreeGateway;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * WorkbenchWorktreeGateway 的进程实现，委托已有的 {@link GitWorktreeGateway}。
 *
 * @author alex
 * @since 2026-08-07
 */
@Component
public class ProcessWorkbenchWorktreeGateway implements WorkbenchWorktreeGateway {

    private final GitWorktreeGateway gitWorktreeGateway;

    public ProcessWorkbenchWorktreeGateway(GitWorktreeGateway gitWorktreeGateway) {
        this.gitWorktreeGateway = gitWorktreeGateway;
    }

    @Override
    public String createWorktree(
            String primaryRepositoryRoot, Path worktreePath, String branch)
            throws IOException, InterruptedException {
        File repoDir = new File(primaryRepositoryRoot);
        String startPoint = gitWorktreeGateway.headCommit(repoDir);
        GitExecResult result = gitWorktreeGateway.addWorktree(
                repoDir, worktreePath, branch, startPoint);
        if (!result.isSuccess()) {
            throw new IOException(
                    "failed to create worktree at " + worktreePath
                            + ": " + result.output().trim());
        }
        return worktreePath.toAbsolutePath().normalize().toString();
    }

    @Override
    public void removeWorktree(
            String primaryRepositoryRoot, Path worktreePath, String branch)
            throws IOException, InterruptedException {
        File repoDir = new File(primaryRepositoryRoot);
        try {
            gitWorktreeGateway.removeWorktree(repoDir, worktreePath);
        } finally {
            gitWorktreeGateway.deleteBranch(repoDir, branch);
        }
    }
}
