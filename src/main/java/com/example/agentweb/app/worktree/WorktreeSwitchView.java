package com.example.agentweb.app.worktree;

import java.util.List;

/**
 * switchBranch 结果视图。JSON 契约: {@code {worktreePath, branch, repos[]}}。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public record WorktreeSwitchView(String worktreePath, String branch, List<WorktreeRepoSwitchView> repos) {
}
