package com.example.agentweb.app.worktree;

import java.nio.file.Path;
import java.util.List;

/**
 * worktree base 下一次遍历的叶子分类结果。
 *
 * @param links         链接叶子(symlink / NTFS junction, fallback 复用主仓 checkout)
 * @param realWorktrees 真实 worktree(含 {@code .git})
 */
public record WorktreeLeaves(List<Path> links, List<Path> realWorktrees) {

    public int totalCount() {
        return links.size() + realWorktrees.size();
    }
}
