package com.example.agentweb.app.workbench.port;

import com.example.agentweb.domain.workspace.RepositoryScope;

/**
 * 确保 Workbench handoff 和 worktree 产物目录被仓库忽略，防止误提交或污染快照。
 *
 * @author alex
 * @since 2026-08-06
 */
public interface WorkspaceHandoffGuard {

    /**
     * 确保主仓库 .gitignore 包含 Workbench 自有产物忽略条目。
     *
     * @param scope 已解析的仓库范围
     */
    void ensureHandoffIgnored(RepositoryScope scope);
}
