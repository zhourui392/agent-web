package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.WorkspaceInspection;
import com.example.agentweb.app.workbench.WorkspaceInspectionSource;
import com.example.agentweb.app.workbench.WorkspaceRepositoryCandidate;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Workspace 检查响应，不包含仓库绝对路径或 Git 原始输出。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceInspectionResponse {

    private final String workspaceRootDisplay;
    private final String inspectionToken;
    private final WorkspaceInspectionSource source;
    private final List<WorkspaceRepositoryCandidateResponse> repositories;
    private final List<String> warnings;

    private WorkspaceInspectionResponse(
            String workspaceRootDisplay, String inspectionToken,
            WorkspaceInspectionSource source,
            List<WorkspaceRepositoryCandidateResponse> repositories,
            List<String> warnings) {
        this.workspaceRootDisplay = workspaceRootDisplay;
        this.inspectionToken = inspectionToken;
        this.source = source;
        this.repositories = repositories;
        this.warnings = warnings;
    }

    public static WorkspaceInspectionResponse from(WorkspaceInspection inspection) {
        List<WorkspaceRepositoryCandidateResponse> repositories =
                new ArrayList<WorkspaceRepositoryCandidateResponse>();
        for (WorkspaceRepositoryCandidate candidate : inspection.getRepositories()) {
            repositories.add(WorkspaceRepositoryCandidateResponse.from(candidate));
        }
        return new WorkspaceInspectionResponse(
                inspection.getWorkspaceRootDisplay(), inspection.getInspectionToken(),
                inspection.getSource(), repositories, inspection.getWarnings());
    }
}
