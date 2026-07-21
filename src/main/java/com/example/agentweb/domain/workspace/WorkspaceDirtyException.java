package com.example.agentweb.domain.workspace;

import lombok.Getter;

/**
 * 工作区有未交付改动且未 force 时拒绝释放。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public class WorkspaceDirtyException extends RuntimeException {

    private final String workspaceId;
    private final transient DirtyReport report;

    public WorkspaceDirtyException(String workspaceId, DirtyReport report) {
        super("workspace dirty, release rejected: " + workspaceId
                + " uncommitted=" + report.getUncommittedFiles().size()
                + " unpushed=" + report.getUnpushedCommits());
        this.workspaceId = workspaceId;
        this.report = report;
    }
}
