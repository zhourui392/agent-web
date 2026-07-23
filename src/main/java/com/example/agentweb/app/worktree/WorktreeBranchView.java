package com.example.agentweb.app.worktree;

/**
 * listWorktrees 单分支条目。JSON 契约: {@code {branch, path, repoCount}}。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public record WorktreeBranchView(String branch, String path, int repoCount) {
}
