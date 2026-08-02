package com.example.agentweb.app.workbench.port;

import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
import com.example.agentweb.domain.workspace.RepositoryScope;

/**
 * 在已授权 Repository Scope 内探测安全开发上下文的只读出站端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkspaceDevelopmentContextGateway {

    WorkspaceDevelopmentContext inspect(RepositoryScope repositoryScope);
}
