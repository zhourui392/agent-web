package com.example.agentweb.app.workbench.admin;

import lombok.Getter;

/**
 * Admin Workbench 安全列表项，不包含目标机器物理路径。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchListItemView {

    private final String workbenchId;
    private final String ownerId;
    private final String ownerName;
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

    public AdminWorkbenchListItemView(
            String workbenchId, String ownerId, String ownerName,
            String title, String status, String agentType,
            String environment, String primaryRepositoryKey,
            int repositoryCount, String activeWriteRunId,
            long createdAt, long updatedAt, long version) {
        this.workbenchId = workbenchId;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
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
