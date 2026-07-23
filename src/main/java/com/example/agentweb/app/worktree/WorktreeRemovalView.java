package com.example.agentweb.app.worktree;

/**
 * 删除 Worktree 的稳定响应视图。
 *
 * @author alex
 * @since 2026-07-23
 */
public record WorktreeRemovalView(boolean success) {

    public static WorktreeRemovalView removed() {
        return new WorktreeRemovalView(true);
    }
}
