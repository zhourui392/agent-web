package com.example.agentweb.app.workbench.port;

import com.example.agentweb.app.workbench.WorkspaceInspection;

/**
 * Workspace 候选仓库的只读发现端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkspaceInspector {

    WorkspaceInspection inspect(String workspaceRoot);
}
