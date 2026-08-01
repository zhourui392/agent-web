package com.example.agentweb.app.workbench;

import com.example.agentweb.app.workbench.port.WorkspaceInspector;
import org.springframework.stereotype.Service;

/**
 * Workspace 只读检查用例，只负责编排检查端口。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class WorkspaceInspectionAppService {

    private final WorkspaceInspector workspaceInspector;

    public WorkspaceInspectionAppService(WorkspaceInspector workspaceInspector) {
        this.workspaceInspector = workspaceInspector;
    }

    public WorkspaceInspection inspect(String workspaceRoot) {
        return workspaceInspector.inspect(workspaceRoot);
    }
}
