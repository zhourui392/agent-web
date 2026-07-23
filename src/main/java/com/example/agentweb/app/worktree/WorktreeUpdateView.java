package com.example.agentweb.app.worktree;

import java.util.List;

/**
 * updateBranch 结果视图。JSON 契约: {@code {branch, repos[]}}。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public record WorktreeUpdateView(String branch, List<WorktreeRepoUpdateView> repos) {
}
