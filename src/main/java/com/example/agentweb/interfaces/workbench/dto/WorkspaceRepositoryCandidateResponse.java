package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.WorkspaceRepositoryCandidate;
import lombok.Getter;

import java.util.List;

/**
 * 可由客户端选择的非敏感仓库候选。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceRepositoryCandidateResponse {

    private final String repositoryKey;
    private final String relativePath;
    private final String branch;
    private final String headShort;
    private final boolean clean;
    private final boolean selectedByDefault;
    private final boolean primarySuggested;
    private final List<String> warnings;

    private WorkspaceRepositoryCandidateResponse(
            String repositoryKey, String relativePath, String branch,
            String headShort, boolean clean, boolean selectedByDefault,
            boolean primarySuggested, List<String> warnings) {
        this.repositoryKey = repositoryKey;
        this.relativePath = relativePath;
        this.branch = branch;
        this.headShort = headShort;
        this.clean = clean;
        this.selectedByDefault = selectedByDefault;
        this.primarySuggested = primarySuggested;
        this.warnings = warnings;
    }

    public static WorkspaceRepositoryCandidateResponse from(
            WorkspaceRepositoryCandidate candidate) {
        return new WorkspaceRepositoryCandidateResponse(
                candidate.getRepositoryKey(), candidate.getRelativePath(),
                candidate.getBranch(), candidate.getHeadShort(), candidate.isClean(),
                candidate.isSelectedByDefault(), candidate.isPrimarySuggested(),
                candidate.getWarnings());
    }
}
