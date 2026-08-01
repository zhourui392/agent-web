package com.example.agentweb.app.workbench;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inspect 返回的非敏感 Git 仓库候选视图，不包含服务器绝对仓库根。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceRepositoryCandidate {

    private final String repositoryKey;
    private final String relativePath;
    private final String branch;
    private final String headShort;
    private final boolean clean;
    private final boolean selectedByDefault;
    private final boolean primarySuggested;
    private final List<String> warnings;

    public WorkspaceRepositoryCandidate(String repositoryKey, String relativePath,
                                        String branch, String headShort, boolean clean,
                                        boolean selectedByDefault, boolean primarySuggested,
                                        List<String> warnings) {
        this.repositoryKey = repositoryKey;
        this.relativePath = relativePath;
        this.branch = branch;
        this.headShort = headShort;
        this.clean = clean;
        this.selectedByDefault = selectedByDefault;
        this.primarySuggested = primarySuggested;
        this.warnings = Collections.unmodifiableList(warnings == null
                ? Collections.<String>emptyList() : new ArrayList<String>(warnings));
    }
}
