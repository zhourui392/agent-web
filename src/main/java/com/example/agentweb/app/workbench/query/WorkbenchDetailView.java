package com.example.agentweb.app.workbench.query;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Stage-only Workbench Owner 详情读模型。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchDetailView {

    private final String id;
    private final String title;
    private final String originalGoal;
    private final String agentType;
    private final String environment;
    private final String activeWriteRunId;
    private final boolean useWorktree;
    private final String worktreeBranch;
    private final String status;
    private final long createdAt;
    private final long updatedAt;
    private final long version;
    private final RepositoryScopeView repositoryScope;
    private final CreationSnapshotView creationSnapshot;
    private final List<StageView> stages;

    public WorkbenchDetailView(
            String id, String title, String originalGoal,
            String agentType, String environment,
            String activeWriteRunId, boolean useWorktree,
            String worktreeBranch, String status,
            long createdAt, long updatedAt, long version,
            RepositoryScopeView repositoryScope,
            CreationSnapshotView creationSnapshot,
            List<StageView> stages) {
        this.id = id;
        this.title = title;
        this.originalGoal = originalGoal;
        this.agentType = agentType;
        this.environment = environment;
        this.activeWriteRunId = activeWriteRunId;
        this.useWorktree = useWorktree;
        this.worktreeBranch = worktreeBranch;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.repositoryScope = Objects.requireNonNull(
                repositoryScope, "repositoryScope");
        this.creationSnapshot = Objects.requireNonNull(
                creationSnapshot, "creationSnapshot");
        this.stages = immutable(stages, "stages");
    }

    private static <T> List<T> immutable(
            List<T> values, String name) {
        return Collections.unmodifiableList(new ArrayList<T>(
                Objects.requireNonNull(values, name)));
    }

    /** 仓库写入范围的安全投影。 */
    @Getter
    public static final class RepositoryScopeView {

        private final String scopeHash;
        private final String primaryRepositoryKey;
        private final String workspaceRoot;
        private final List<RepositoryView> repositories;

        public RepositoryScopeView(
                String scopeHash, String primaryRepositoryKey,
                String workspaceRoot,
                List<RepositoryView> repositories) {
            this.scopeHash = scopeHash;
            this.primaryRepositoryKey = primaryRepositoryKey;
            this.workspaceRoot = workspaceRoot;
            this.repositories = immutable(repositories, "repositories");
        }
    }

    /** 单个仓库的逻辑身份。 */
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

    /** Workbench 创建时 Workspace Snapshot 的不可变引用。 */
    @Getter
    public static final class CreationSnapshotView {

        private final String snapshotId;
        private final String topologyHash;
        private final String stateHash;
        private final int repositoryCount;

        public CreationSnapshotView(
                String snapshotId, String topologyHash,
                String stateHash, int repositoryCount) {
            this.snapshotId = snapshotId;
            this.topologyHash = topologyHash;
            this.stateHash = stateHash;
            this.repositoryCount = repositoryCount;
        }
    }

    /** 冻结 Stage Snapshot 与实例状态的安全投影。 */
    @Getter
    public static final class StageView {

        private final String stageInstanceIdentifier;
        private final String definitionIdentifier;
        private final long definitionRevision;
        private final String definitionHash;
        private final String snapshotHash;
        private final int sequenceNumber;
        private final String displayName;
        private final String description;
        private final List<String> allowedRunModes;
        private final String status;
        private final int conversationGeneration;
        private final ConversationView currentConversation;
        private final List<ConversationView> conversationHistory;
        private final ActiveRunView activeRun;
        private final Long lastActivityAt;
        private final Long completedAt;

        public StageView(
                String stageInstanceIdentifier,
                String definitionIdentifier,
                long definitionRevision,
                String definitionHash, String snapshotHash,
                int sequenceNumber, String displayName,
                String description, List<String> allowedRunModes,
                String status,
                int conversationGeneration,
                ConversationView currentConversation,
                List<ConversationView> conversationHistory,
                ActiveRunView activeRun,
                Long lastActivityAt, Long completedAt) {
            this.stageInstanceIdentifier = stageInstanceIdentifier;
            this.definitionIdentifier = definitionIdentifier;
            this.definitionRevision = definitionRevision;
            this.definitionHash = definitionHash;
            this.snapshotHash = snapshotHash;
            this.sequenceNumber = sequenceNumber;
            this.displayName = displayName;
            this.description = description;
            this.allowedRunModes = immutable(
                    allowedRunModes, "allowedRunModes");
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

    /** Stage 的一个稳定会话代际。 */
    @Getter
    public static final class ConversationView {

        private final String sessionId;
        private final int generation;
        private final long createdAt;
        private final Long retiredAt;

        public ConversationView(
                String sessionId, int generation,
                long createdAt, Long retiredAt) {
            this.sessionId = sessionId;
            this.generation = generation;
            this.createdAt = createdAt;
            this.retiredAt = retiredAt;
        }
    }

    /** Stage 当前活动 Run。 */
    @Getter
    public static final class ActiveRunView {

        private final String runId;
        private final String runMode;
        private final long preparedAt;

        public ActiveRunView(
                String runId, String runMode, long preparedAt) {
            this.runId = runId;
            this.runMode = runMode;
            this.preparedAt = preparedAt;
        }
    }
}
