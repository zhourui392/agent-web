package com.example.agentweb.app.workbench.query;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Workbench Owner 详情读模型。仅返回仓库逻辑身份，不暴露绝对路径或根指纹。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchDetailView {

    private final String id;
    private final String title;
    private final String originalGoal;
    private final String agentType;
    private final String environment;
    private final String activeWriteRunId;
    private final String status;
    private final long createdAt;
    private final long updatedAt;
    private final long version;
    private final RepositoryScopeView repositoryScope;
    private final CreationSnapshotView creationSnapshot;
    private final List<PhaseView> phases;

    public WorkbenchDetailView(
            String id,
            String title,
            String originalGoal,
            String agentType,
            String environment,
            String activeWriteRunId,
            String status,
            long createdAt,
            long updatedAt,
            long version,
            RepositoryScopeView repositoryScope,
            CreationSnapshotView creationSnapshot,
            List<PhaseView> phases) {
        this.id = id;
        this.title = title;
        this.originalGoal = originalGoal;
        this.agentType = agentType;
        this.environment = environment;
        this.activeWriteRunId = activeWriteRunId;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.repositoryScope = Objects.requireNonNull(repositoryScope, "repositoryScope");
        this.creationSnapshot = Objects.requireNonNull(creationSnapshot, "creationSnapshot");
        this.phases = immutable(phases, "phases");
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        return Collections.unmodifiableList(new ArrayList<T>(
                Objects.requireNonNull(values, name)));
    }

    /** 仓库写入范围的安全投影。 */
    @Getter
    public static final class RepositoryScopeView {

        private final String scopeHash;
        private final String primaryRepositoryKey;
        private final List<RepositoryView> repositories;

        public RepositoryScopeView(
                String scopeHash,
                String primaryRepositoryKey,
                List<RepositoryView> repositories) {
            this.scopeHash = scopeHash;
            this.primaryRepositoryKey = primaryRepositoryKey;
            this.repositories = immutable(repositories, "repositories");
        }
    }

    /** 单个仓库的逻辑身份，不包含 repository_root 和 root_fingerprint。 */
    @Getter
    public static final class RepositoryView {

        private final String repositoryKey;
        private final String relativePath;
        private final boolean primary;

        public RepositoryView(
                String repositoryKey,
                String relativePath,
                boolean primary) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
            this.primary = primary;
        }
    }

    /** Workbench 创建时 Workspace Snapshot 的不可变引用。 */
    @Getter
    public static final class CreationSnapshotView {

        private final String snapshotId;
        private final String topologyHash;
        private final String stateHash;
        private final int repositoryCount;

        public CreationSnapshotView(
                String snapshotId,
                String topologyHash,
                String stateHash,
                int repositoryCount) {
            this.snapshotId = snapshotId;
            this.topologyHash = topologyHash;
            this.stateHash = stateHash;
            this.repositoryCount = repositoryCount;
        }
    }

    /** 固定 Phase、会话代际与当前活动 Run 的恢复投影。 */
    @Getter
    public static final class PhaseView {

        private final String phase;
        private final int phaseOrder;
        private final String status;
        private final int conversationGeneration;
        private final ConversationView currentConversation;
        private final List<ConversationView> conversationHistory;
        private final ActiveRunView activeRun;
        private final Long lastActivityAt;
        private final Long completedAt;

        public PhaseView(
                String phase,
                int phaseOrder,
                String status,
                int conversationGeneration,
                ConversationView currentConversation,
                List<ConversationView> conversationHistory,
                ActiveRunView activeRun,
                Long lastActivityAt,
                Long completedAt) {
            this.phase = phase;
            this.phaseOrder = phaseOrder;
            this.status = status;
            this.conversationGeneration = conversationGeneration;
            this.currentConversation = currentConversation;
            this.conversationHistory = immutable(
                    conversationHistory, "conversationHistory");
            this.activeRun = activeRun;
            this.lastActivityAt = lastActivityAt;
            this.completedAt = completedAt;
        }
    }

    /** Phase 的一个稳定会话代际。 */
    @Getter
    public static final class ConversationView {

        private final String sessionId;
        private final int generation;
        private final long createdAt;
        private final Long retiredAt;

        public ConversationView(
                String sessionId,
                int generation,
                long createdAt,
                Long retiredAt) {
            this.sessionId = sessionId;
            this.generation = generation;
            this.createdAt = createdAt;
            this.retiredAt = retiredAt;
        }
    }

    /** Phase 当前活动 Run 及 Review 写意图证明。 */
    @Getter
    public static final class ActiveRunView {

        private final String runId;
        private final String runMode;
        private final long preparedAt;
        private final String reviewConfirmationId;
        private final Long reviewOpinionVersion;
        private final String reviewOpinionHash;

        public ActiveRunView(
                String runId,
                String runMode,
                long preparedAt,
                String reviewConfirmationId,
                Long reviewOpinionVersion,
                String reviewOpinionHash) {
            this.runId = runId;
            this.runMode = runMode;
            this.preparedAt = preparedAt;
            this.reviewConfirmationId = reviewConfirmationId;
            this.reviewOpinionVersion = reviewOpinionVersion;
            this.reviewOpinionHash = reviewOpinionHash;
        }
    }
}
