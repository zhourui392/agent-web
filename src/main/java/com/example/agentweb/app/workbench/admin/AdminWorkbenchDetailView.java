package com.example.agentweb.app.workbench.admin;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Admin Workbench 安全详情；只投影逻辑仓库身份和阶段状态。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchDetailView {

    private final String workbenchId;
    private final String ownerId;
    private final String ownerName;
    private final String title;
    private final String status;
    private final String agentType;
    private final String environment;
    private final String primaryRepositoryKey;
    private final String repositoryScopeHash;
    private final String activeWriteRunId;
    private final long createdAt;
    private final long updatedAt;
    private final long version;
    private final List<RepositoryView> repositories;
    private final List<PhaseView> phases;

    public AdminWorkbenchDetailView(
            String workbenchId, String ownerId, String ownerName,
            String title, String status, String agentType,
            String environment, String primaryRepositoryKey,
            String repositoryScopeHash, String activeWriteRunId,
            long createdAt, long updatedAt, long version,
            List<RepositoryView> repositories,
            List<PhaseView> phases) {
        this.workbenchId = workbenchId;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.title = title;
        this.status = status;
        this.agentType = agentType;
        this.environment = environment;
        this.primaryRepositoryKey = primaryRepositoryKey;
        this.repositoryScopeHash = repositoryScopeHash;
        this.activeWriteRunId = activeWriteRunId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.repositories = immutable(repositories, "repositories");
        this.phases = immutable(phases, "phases");
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    /** 只含 repositoryKey 与仓内相对路径的仓库投影。 */
    @Getter
    public static final class RepositoryView {

        private final String repositoryKey;
        private final String relativePath;
        private final boolean primary;

        public RepositoryView(
                String repositoryKey, String relativePath,
                boolean primary) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
            this.primary = primary;
        }
    }

    /** 只含状态和活动 Run 逻辑引用的阶段投影。 */
    @Getter
    public static final class PhaseView {

        private final String phase;
        private final int phaseOrder;
        private final String status;
        private final String activeRunId;
        private final String activeRunMode;
        private final Long lastActivityAt;
        private final Long completedAt;

        public PhaseView(
                String phase, int phaseOrder, String status,
                String activeRunId, String activeRunMode,
                Long lastActivityAt, Long completedAt) {
            this.phase = phase;
            this.phaseOrder = phaseOrder;
            this.status = status;
            this.activeRunId = activeRunId;
            this.activeRunMode = activeRunMode;
            this.lastActivityAt = lastActivityAt;
            this.completedAt = completedAt;
        }
    }
}
