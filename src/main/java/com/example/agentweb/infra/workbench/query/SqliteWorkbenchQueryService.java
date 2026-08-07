package com.example.agentweb.infra.workbench.query;

import com.example.agentweb.app.workbench.query.WorkbenchDetailView;
import com.example.agentweb.app.workbench.query.WorkbenchListCursor;
import com.example.agentweb.app.workbench.query.WorkbenchListItemView;
import com.example.agentweb.app.workbench.query.WorkbenchListPage;
import com.example.agentweb.app.workbench.query.WorkbenchListRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessagePage;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessageRequest;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessageTooLargeException;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.infra.workbench.WorkbenchStageSnapshotJsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage-only Workbench Owner 侧 SQLite CQRS 投影。
 *
 * @author alex
 * @since 2026-08-05
 */
@Component
@Transactional(readOnly = true)
public class SqliteWorkbenchQueryService implements WorkbenchQueryService {

    private static final int MAX_ID_LENGTH = 128;
    private static final long MAX_MESSAGE_CONTENT_BYTES = 1024L * 1024L;
    private static final String LIST_COLUMNS =
            "w.id, w.title, w.status, w.agent_type, w.environment, "
                    + "w.primary_repository_key, w.active_write_run_id, "
                    + "w.created_at, w.updated_at, w.version, "
                    + "(SELECT COUNT(*) FROM workbench_repository_scope scope "
                    + "WHERE scope.workbench_id=w.id) repository_count ";
    private static final String DETAIL_SQL =
            "SELECT id, title, original_goal, agent_type, environment, "
                    + "active_write_run_id, use_worktree, worktree_branch, "
                    + "status, created_at, updated_at, "
                    + "version, primary_repository_key, repository_scope_hash, "
                    + "creation_snapshot_id, creation_snapshot_topology_hash, "
                    + "creation_snapshot_state_hash, "
                    + "creation_snapshot_repository_count, workspace_root "
                    + "FROM workbench WHERE owner_id=? AND id=?";
    private static final String CURRENT_STAGE_CONVERSATION_SQL =
            "SELECT w.version, s.conversation_generation, "
                    + "c.session_id declared_session_id, cs.id session_id "
                    + "FROM workbench w "
                    + "JOIN workbench_stage s ON s.workbench_id=w.id "
                    + "LEFT JOIN workbench_stage_conversation c "
                    + "ON c.workbench_id=s.workbench_id "
                    + "AND c.stage_instance_identifier="
                    + "s.stage_instance_identifier "
                    + "AND c.generation=s.conversation_generation "
                    + "AND c.retired_at IS NULL "
                    + "LEFT JOIN chat_session cs ON cs.id=c.session_id "
                    + "AND cs.session_kind='WORKBENCH_STAGE' "
                    + "AND cs.retired_at IS NULL "
                    + "AND cs.context_id=w.id || ':' "
                    + "|| s.stage_instance_identifier "
                    + "AND cs.user_id=w.owner_id "
                    + "AND cs.user_name=w.owner_name "
                    + "WHERE w.owner_id=? AND w.id=? "
                    + "AND s.stage_instance_identifier=?";

    private final JdbcTemplate jdbc;
    private final WorkbenchStageSnapshotJsonMapper stageSnapshotJsonMapper;

    public SqliteWorkbenchQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.stageSnapshotJsonMapper =
                new WorkbenchStageSnapshotJsonMapper(new ObjectMapper());
    }

    @Override
    public WorkbenchListPage listByOwner(
            String ownerId, WorkbenchListRequest request) {
        String validOwnerId = requireIdentity(ownerId, "ownerId");
        Objects.requireNonNull(request, "request");
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(LIST_COLUMNS)
                .append("FROM workbench w WHERE w.owner_id=?");
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(validOwnerId);
        if (request.getStatus() != null) {
            sql.append(" AND w.status=?");
            arguments.add(request.getStatus().name());
        }
        WorkbenchListCursor cursor = request.getCursor();
        if (cursor != null) {
            sql.append(" AND (w.updated_at<? OR "
                    + "(w.updated_at=? AND w.id<?))");
            arguments.add(Long.valueOf(cursor.getUpdatedAt()));
            arguments.add(Long.valueOf(cursor.getUpdatedAt()));
            arguments.add(cursor.getWorkbenchId());
        }
        sql.append(" ORDER BY w.updated_at DESC, w.id DESC LIMIT ?");
        arguments.add(Integer.valueOf(request.getLimit() + 1));
        List<WorkbenchListItemView> fetched = jdbc.query(
                sql.toString(), this::mapListItem,
                arguments.toArray());
        boolean hasNext = fetched.size() > request.getLimit();
        List<WorkbenchListItemView> items = hasNext
                ? new ArrayList<WorkbenchListItemView>(
                fetched.subList(0, request.getLimit())) : fetched;
        WorkbenchListCursor nextCursor = null;
        if (hasNext) {
            WorkbenchListItemView last = items.get(items.size() - 1);
            nextCursor = new WorkbenchListCursor(
                    last.getUpdatedAt(), last.getId());
        }
        return new WorkbenchListPage(items, nextCursor);
    }

    @Override
    public Optional<WorkbenchDetailView> findDetailByOwner(
            String ownerId, String workbenchId) {
        String validOwnerId = requireIdentity(ownerId, "ownerId");
        String validWorkbenchId = requireIdentity(
                workbenchId, "workbenchId");
        List<WorkbenchRow> roots = jdbc.query(
                DETAIL_SQL, this::mapWorkbenchRow,
                validOwnerId, validWorkbenchId);
        if (roots.isEmpty()) {
            return Optional.empty();
        }
        WorkbenchRow root = roots.get(0);
        WorkbenchDetailView.RepositoryScopeView repositoryScope =
                new WorkbenchDetailView.RepositoryScopeView(
                        root.getRepositoryScopeHash(),
                        root.getPrimaryRepositoryKey(),
                        root.getWorkspaceRoot(),
                        loadRepositories(root.getId()));
        WorkbenchDetailView.CreationSnapshotView creationSnapshot =
                new WorkbenchDetailView.CreationSnapshotView(
                        root.getCreationSnapshotId(),
                        root.getCreationSnapshotTopologyHash(),
                        root.getCreationSnapshotStateHash(),
                        root.getCreationSnapshotRepositoryCount());
        List<WorkbenchDetailView.StageView> stages =
                loadStages(root.getId());
        if (stages.isEmpty()) {
            throw new IllegalStateException(
                    "Corrupt Workbench has no Stage: " + root.getId());
        }
        return Optional.of(new WorkbenchDetailView(
                root.getId(), root.getTitle(), root.getOriginalGoal(),
                root.getAgentType(), root.getEnvironment(),
                root.getActiveWriteRunId(), root.isUseWorktree(),
                root.getWorktreeBranch(), root.getStatus(),
                root.getCreatedAt(), root.getUpdatedAt(), root.getVersion(),
                repositoryScope, creationSnapshot, stages));
    }

    @Override
    public Optional<WorkbenchStageConversationMessagePage>
            findCurrentStageConversationByOwner(
                    String ownerId, String workbenchId,
                    String stageInstanceIdentifier,
                    WorkbenchStageConversationMessageRequest request) {
        String validOwnerId = requireIdentity(ownerId, "ownerId");
        String validWorkbenchId = requireIdentity(
                workbenchId, "workbenchId");
        String validStageIdentifier = requireIdentity(
                stageInstanceIdentifier, "stageInstanceIdentifier");
        Objects.requireNonNull(request, "request");
        List<CurrentStageConversationRow> rows = jdbc.query(
                CURRENT_STAGE_CONVERSATION_SQL,
                (resultSet, rowNumber) ->
                        new CurrentStageConversationRow(
                                resultSet.getString("declared_session_id"),
                                resultSet.getString("session_id"),
                                resultSet.getInt("conversation_generation"),
                                resultSet.getLong("version")),
                validOwnerId, validWorkbenchId, validStageIdentifier);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        CurrentStageConversationRow row = rows.get(0);
        row.requireTrustedSessionBinding();
        List<WorkbenchStageConversationMessagePage.MessageView> messages =
                row.getSessionId() == null
                        ? new ArrayList<WorkbenchStageConversationMessagePage
                        .MessageView>()
                        : loadStageConversationMessages(
                                row.getSessionId(),
                                validWorkbenchId + ":" + validStageIdentifier,
                                request);
        boolean hasMore = messages.size() > request.getLimit();
        if (hasMore) {
            messages = new ArrayList<
                    WorkbenchStageConversationMessagePage.MessageView>(
                    messages.subList(0, request.getLimit()));
        }
        Collections.reverse(messages);
        Long nextCursor = hasMore
                ? Long.valueOf(messages.get(0).getMessageId()) : null;
        return Optional.of(new WorkbenchStageConversationMessagePage(
                row.getSessionId(), row.getGeneration(),
                row.getWorkbenchVersion(), messages, nextCursor));
    }

    private WorkbenchListItemView mapListItem(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkbenchListItemView(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("status"),
                resultSet.getString("agent_type"),
                resultSet.getString("environment"),
                resultSet.getString("primary_repository_key"),
                resultSet.getInt("repository_count"),
                resultSet.getString("active_write_run_id"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"),
                resultSet.getLong("version"));
    }

    private WorkbenchRow mapWorkbenchRow(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkbenchRow(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("original_goal"),
                resultSet.getString("agent_type"),
                resultSet.getString("environment"),
                resultSet.getString("active_write_run_id"),
                resultSet.getInt("use_worktree") != 0,
                resultSet.getString("worktree_branch"),
                resultSet.getString("status"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"),
                resultSet.getLong("version"),
                resultSet.getString("primary_repository_key"),
                resultSet.getString("repository_scope_hash"),
                resultSet.getString("workspace_root"),
                resultSet.getString("creation_snapshot_id"),
                resultSet.getString(
                        "creation_snapshot_topology_hash"),
                resultSet.getString("creation_snapshot_state_hash"),
                resultSet.getInt(
                        "creation_snapshot_repository_count"));
    }

    private List<WorkbenchDetailView.RepositoryView> loadRepositories(
            String workbenchId) {
        return jdbc.query(
                "SELECT repository_key, relative_path, primary_repository "
                        + "FROM workbench_repository_scope "
                        + "WHERE workbench_id=? "
                        + "ORDER BY primary_repository DESC, "
                        + "repository_key ASC",
                (resultSet, rowNumber) ->
                        new WorkbenchDetailView.RepositoryView(
                                resultSet.getString("repository_key"),
                                resultSet.getString("relative_path"),
                                resultSet.getInt(
                                        "primary_repository") != 0),
                workbenchId);
    }

    private List<WorkbenchStageConversationMessagePage.MessageView>
            loadStageConversationMessages(
                    String sessionId, String originReference,
                    WorkbenchStageConversationMessageRequest request) {
        StringBuilder sql = conversationMessageSql();
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(originReference);
        arguments.add(sessionId);
        if (request.getBeforeMessageId() != null) {
            sql.append("AND m.id<? ");
            arguments.add(request.getBeforeMessageId());
        }
        sql.append("ORDER BY m.id DESC LIMIT ?");
        arguments.add(Integer.valueOf(request.getLimit() + 1));
        return jdbc.query(
                sql.toString(),
                (resultSet, rowNumber) ->
                        new WorkbenchStageConversationMessagePage.MessageView(
                                resultSet.getLong("id"),
                                resultSet.getString("role"),
                                boundedStageMessageContent(resultSet),
                                resultSet.getString("timestamp"),
                                resultSet.getString("run_id")),
                arguments.toArray());
    }

    private StringBuilder conversationMessageSql() {
        return new StringBuilder(
                "SELECT m.id, m.role, "
                        + "CASE WHEN length(CAST(m.content AS BLOB))<="
                        + MAX_MESSAGE_CONTENT_BYTES
                        + " THEN m.content ELSE NULL END content, "
                        + "m.timestamp, "
                        + "length(CAST(m.content AS BLOB)) content_bytes, "
                        + "(SELECT r.id FROM chat_run r "
                        + "WHERE r.session_id=m.session_id "
                        + "AND r.run_origin='WORKBENCH' "
                        + "AND r.origin_reference=? "
                        + "AND r.execution_context_id=r.id "
                        + "AND (r.user_message_id=m.id "
                        + "OR r.assistant_message_id=m.id) "
                        + "ORDER BY r.created_at DESC LIMIT 1) run_id "
                        + "FROM chat_message m WHERE m.session_id=? ");
    }

    private String boundedStageMessageContent(ResultSet resultSet)
            throws SQLException {
        if (resultSet.getLong("content_bytes")
                > MAX_MESSAGE_CONTENT_BYTES) {
            throw new WorkbenchStageConversationMessageTooLargeException();
        }
        return resultSet.getString("content");
    }

    private List<WorkbenchDetailView.StageView> loadStages(
            String workbenchId) {
        Map<String, List<WorkbenchDetailView.ConversationView>> conversations =
                loadStageConversations(workbenchId);
        return jdbc.query(
                "SELECT stage_instance_identifier, definition_identifier, "
                        + "definition_revision, definition_hash, "
                        + "sequence_number, stage_snapshot_json, "
                        + "stage_snapshot_hash, status, "
                        + "conversation_generation, active_run_id, "
                        + "active_run_mode, active_run_prepared_at, "
                        + "last_activity_at, completed_at "
                        + "FROM workbench_stage WHERE workbench_id=? "
                        + "ORDER BY sequence_number ASC",
                (resultSet, rowNumber) -> mapStage(
                        workbenchId, resultSet, conversations),
                workbenchId);
    }

    private WorkbenchDetailView.StageView mapStage(
            String workbenchId, ResultSet resultSet,
            Map<String, List<WorkbenchDetailView.ConversationView>>
                    conversations) throws SQLException {
        String stageIdentifier = resultSet.getString(
                "stage_instance_identifier");
        WorkbenchStageSnapshot snapshot;
        try {
            snapshot = stageSnapshotJsonMapper.read(
                    resultSet.getString("stage_snapshot_json"),
                    resultSet.getString("stage_snapshot_hash"));
        } catch (IllegalStateException failure) {
            throw new IllegalStateException(
                    "Corrupt Workbench Stage detail "
                            + workbenchId + ":" + stageIdentifier,
                    failure);
        }
        requireSnapshotColumns(
                workbenchId, stageIdentifier, resultSet, snapshot);
        int currentGeneration = resultSet.getInt(
                "conversation_generation");
        List<WorkbenchDetailView.ConversationView> allConversations =
                conversations.getOrDefault(
                        stageIdentifier, Collections.emptyList());
        WorkbenchDetailView.ConversationView currentConversation = null;
        List<WorkbenchDetailView.ConversationView> conversationHistory =
                new ArrayList<WorkbenchDetailView.ConversationView>();
        for (WorkbenchDetailView.ConversationView conversation
                : allConversations) {
            if (conversation.getGeneration() == currentGeneration
                    && conversation.getRetiredAt() == null) {
                currentConversation = conversation;
            } else {
                conversationHistory.add(conversation);
            }
        }
        return new WorkbenchDetailView.StageView(
                stageIdentifier, snapshot.getDefinitionIdentifier(),
                snapshot.getDefinitionRevision(),
                snapshot.getDefinitionHash(), snapshot.getSnapshotHash(),
                snapshot.getSequenceNumber(), snapshot.getDisplayName(),
                snapshot.getDescription(),
                snapshot.getAllowedRunModes().stream()
                        .map(Enum::name).toList(),
                resultSet.getString("status"),
                currentGeneration, currentConversation, conversationHistory,
                mapStageActiveRun(resultSet),
                nullableLong(resultSet, "last_activity_at"),
                nullableLong(resultSet, "completed_at"));
    }

    private Map<String, List<WorkbenchDetailView.ConversationView>>
            loadStageConversations(String workbenchId) {
        List<StageConversationRow> rows = jdbc.query(
                "SELECT stage_instance_identifier, session_id, generation, "
                        + "created_at, retired_at "
                        + "FROM workbench_stage_conversation "
                        + "WHERE workbench_id=? "
                        + "ORDER BY stage_instance_identifier, generation",
                (resultSet, rowNumber) -> new StageConversationRow(
                        resultSet.getString("stage_instance_identifier"),
                        new WorkbenchDetailView.ConversationView(
                                resultSet.getString("session_id"),
                                resultSet.getInt("generation"),
                                resultSet.getLong("created_at"),
                                nullableLong(resultSet, "retired_at"))),
                workbenchId);
        Map<String, List<WorkbenchDetailView.ConversationView>> byStage =
                new HashMap<String,
                        List<WorkbenchDetailView.ConversationView>>();
        for (StageConversationRow row : rows) {
            byStage.computeIfAbsent(
                    row.getStageInstanceIdentifier(),
                    ignored -> new ArrayList<
                            WorkbenchDetailView.ConversationView>())
                    .add(row.getView());
        }
        return byStage;
    }

    private void requireSnapshotColumns(
            String workbenchId, String stageInstanceIdentifier,
            ResultSet resultSet, WorkbenchStageSnapshot snapshot)
            throws SQLException {
        if (!snapshot.getDefinitionIdentifier().equals(
                resultSet.getString("definition_identifier"))
                || snapshot.getDefinitionRevision()
                != resultSet.getLong("definition_revision")
                || !snapshot.getDefinitionHash().equals(
                resultSet.getString("definition_hash"))
                || snapshot.getSequenceNumber()
                != resultSet.getInt("sequence_number")) {
            throw new IllegalStateException(
                    "Corrupt Workbench Stage columns "
                            + workbenchId + ":" + stageInstanceIdentifier);
        }
    }

    private WorkbenchDetailView.ActiveRunView mapStageActiveRun(
            ResultSet resultSet) throws SQLException {
        String runIdentifier = resultSet.getString("active_run_id");
        if (runIdentifier == null) {
            return null;
        }
        return new WorkbenchDetailView.ActiveRunView(
                runIdentifier, resultSet.getString("active_run_mode"),
                resultSet.getLong("active_run_prepared_at"));
    }

    private static Long nullableLong(
            ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Long.valueOf(value);
    }

    private static String requireIdentity(String value, String name) {
        if (value == null || value.trim().isEmpty()
                || value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to "
                            + MAX_ID_LENGTH + " characters");
        }
        return value;
    }

    @Getter
    private static final class WorkbenchRow {
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
        private final String primaryRepositoryKey;
        private final String repositoryScopeHash;
        private final String workspaceRoot;
        private final String creationSnapshotId;
        private final String creationSnapshotTopologyHash;
        private final String creationSnapshotStateHash;
        private final int creationSnapshotRepositoryCount;

        private WorkbenchRow(
                String id, String title, String originalGoal,
                String agentType, String environment,
                String activeWriteRunId, boolean useWorktree,
                String worktreeBranch, String status,
                long createdAt, long updatedAt, long version,
                String primaryRepositoryKey,
                String repositoryScopeHash, String workspaceRoot,
                String creationSnapshotId,
                String creationSnapshotTopologyHash,
                String creationSnapshotStateHash,
                int creationSnapshotRepositoryCount) {
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
            this.primaryRepositoryKey = primaryRepositoryKey;
            this.repositoryScopeHash = repositoryScopeHash;
            this.workspaceRoot = workspaceRoot;
            this.creationSnapshotId = creationSnapshotId;
            this.creationSnapshotTopologyHash =
                    creationSnapshotTopologyHash;
            this.creationSnapshotStateHash = creationSnapshotStateHash;
            this.creationSnapshotRepositoryCount =
                    creationSnapshotRepositoryCount;
        }
    }

    @Getter
    private static final class StageConversationRow {
        private final String stageInstanceIdentifier;
        private final WorkbenchDetailView.ConversationView view;

        private StageConversationRow(
                String stageInstanceIdentifier,
                WorkbenchDetailView.ConversationView view) {
            this.stageInstanceIdentifier = stageInstanceIdentifier;
            this.view = view;
        }
    }

    @Getter
    private static final class CurrentStageConversationRow {
        private final String declaredSessionId;
        private final String sessionId;
        private final int generation;
        private final long workbenchVersion;

        private CurrentStageConversationRow(
                String declaredSessionId, String sessionId,
                int generation, long workbenchVersion) {
            this.declaredSessionId = declaredSessionId;
            this.sessionId = sessionId;
            this.generation = generation;
            this.workbenchVersion = workbenchVersion;
        }

        private void requireTrustedSessionBinding() {
            if (!Objects.equals(declaredSessionId, sessionId)) {
                throw new IllegalStateException(
                        "Stage conversation Session binding is corrupted");
            }
        }
    }
}
