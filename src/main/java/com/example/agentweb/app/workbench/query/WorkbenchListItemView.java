package com.example.agentweb.app.workbench.query;

import lombok.Getter;

/**
 * Workbench Owner 列表项读模型，不包含服务端绝对路径。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchListItemView {

    private final String id;
    private final String title;
    private final String status;
    private final String agentType;
    private final String environment;
    private final String primaryRepositoryKey;
    private final int repositoryCount;
    private final String activeWriteRunId;
    private final long createdAt;
    private final long updatedAt;
    private final long version;

    public WorkbenchListItemView(
            String id,
            String title,
            String status,
            String agentType,
            String environment,
            String primaryRepositoryKey,
            int repositoryCount,
            String activeWriteRunId,
            long createdAt,
            long updatedAt,
            long version) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.agentType = agentType;
        this.environment = environment;
        this.primaryRepositoryKey = primaryRepositoryKey;
        this.repositoryCount = repositoryCount;
        this.activeWriteRunId = activeWriteRunId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }
}
