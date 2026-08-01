package com.example.agentweb.app.workbench.port;

import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;

/**
 * 重新验证显式仓库选择并冻结 Repository Scope 的端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkspaceScopeGateway {

    RepositoryScope resolve(String workspaceRoot, RepositorySelection selection);
}
