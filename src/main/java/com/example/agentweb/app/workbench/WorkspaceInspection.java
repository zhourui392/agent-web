package com.example.agentweb.app.workbench;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次只读 Workspace Inspect 结果。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceInspection {

    private final String workspaceRootDisplay;
    private final String inspectionToken;
    private final WorkspaceInspectionSource source;
    private final List<WorkspaceRepositoryCandidate> repositories;
    private final List<String> warnings;

    public WorkspaceInspection(String workspaceRootDisplay, String inspectionToken,
                               WorkspaceInspectionSource source,
                               List<WorkspaceRepositoryCandidate> repositories,
                               List<String> warnings) {
        this.workspaceRootDisplay = workspaceRootDisplay;
        this.inspectionToken = inspectionToken;
        this.source = source;
        this.repositories = Collections.unmodifiableList(
                new ArrayList<WorkspaceRepositoryCandidate>(repositories));
        this.warnings = Collections.unmodifiableList(warnings == null
                ? Collections.<String>emptyList() : new ArrayList<String>(warnings));
    }
}
