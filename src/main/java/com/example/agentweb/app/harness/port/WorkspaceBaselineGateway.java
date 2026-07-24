package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.domain.harness.WorkspaceChangeEvidence;

/**
 * 读取 Git 工作区基线的出站端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface WorkspaceBaselineGateway {

    WorkspaceBaseline capture(String workingDir);

    default WorkspaceChangeEvidence captureChanges(String workingDir,
                                                    WorkspaceBaseline baseline) {
        throw new UnsupportedOperationException("workspace change evidence is unavailable");
    }
}
