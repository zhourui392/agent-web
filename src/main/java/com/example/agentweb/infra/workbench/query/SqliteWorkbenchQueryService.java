package com.example.agentweb.infra.workbench.query;

import com.example.agentweb.app.workbench.query.WorkbenchDetailView;
import com.example.agentweb.app.workbench.query.WorkbenchListCursor;
import com.example.agentweb.app.workbench.query.WorkbenchListItemView;
import com.example.agentweb.app.workbench.query.WorkbenchListPage;
import com.example.agentweb.app.workbench.query.WorkbenchListRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
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
 * Workbench Owner 侧 SQLite CQRS 投影，不恢复写模型聚合。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@Transactional(readOnly = true)
public class SqliteWorkbenchQueryService implements WorkbenchQueryService {

    private static final int MAX_ID_LENGTH = 128;

    private static final String LIST_COLUMNS =
            "w.id, w.title, w.status, w.agent_type, w.environment, "
                    + "w.primary_repository_key, w.active_write_run_id, w.created_at, "
                    + "w.updated_at, w.version, "
                    + "(SELECT COUNT(*) FROM workbench_repository_scope scope "
                    + "WHERE scope.workbench_id = w.id) AS repository_count ";

    private static final String DETAIL_SQL =
            "SELECT id, title, original_goal, agent_type, environment, active_write_run_id, "
                    + "status, created_at, updated_at, version, primary_repository_key, "
                    + "repository_scope_hash, creation_snapshot_id, "
                    + "creation_snapshot_topology_hash, creation_snapshot_state_hash, "
                    + "creation_snapshot_repository_count FROM workbench "
                    + "WHERE owner_id = ? AND id = ?";

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WorkbenchListPage listByOwner(String ownerId, WorkbenchListRequest request) {
        String validOwnerId = requireIdentity(ownerId, "ownerId");
        Objects.requireNonNull(request, "request");
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(LIST_COLUMNS)
                .append("FROM workbench w WHERE w.owner_id = ?");
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(validOwnerId);
        if (request.getStatus() != null) {
            sql.append(" AND w.status = ?");
            arguments.add(request.getStatus().name());
        }
        WorkbenchListCursor cursor = request.getCursor();
        if (cursor != null) {
            sql.append(" AND (w.updated_at < ? OR (w.updated_at = ? AND w.id < ?))");
            arguments.add(cursor.getUpdatedAt());
            arguments.add(cursor.getUpdatedAt());
            arguments.add(cursor.getWorkbenchId());
        }
        sql.append(" ORDER BY w.updated_at DESC, w.id DESC LIMIT ?");
        arguments.add(request.getLimit() + 1);

        List<WorkbenchListItemView> fetched = jdbc.query(
                sql.toString(), this::mapListItem, arguments.toArray());
        boolean hasNext = fetched.size() > request.getLimit();
        List<WorkbenchListItemView> items = hasNext
                ? new ArrayList<WorkbenchListItemView>(
                        fetched.subList(0, request.getLimit()))
                : fetched;
        WorkbenchListCursor nextCursor = null;
        if (hasNext) {
            WorkbenchListItemView last = items.get(items.size() - 1);
            nextCursor = new WorkbenchListCursor(last.getUpdatedAt(), last.getId());
        }
        return new WorkbenchListPage(items, nextCursor);
    }

    @Override
    public Optional<WorkbenchDetailView> findDetailByOwner(String ownerId, String workbenchId) {
        String validOwnerId = requireIdentity(ownerId, "ownerId");
        String validWorkbenchId = requireIdentity(workbenchId, "workbenchId");
        List<WorkbenchRow> roots = jdbc.query(
                DETAIL_SQL, this::mapWorkbenchRow, validOwnerId, validWorkbenchId);
        if (roots.isEmpty()) {
            return Optional.empty();
        }
        WorkbenchRow root = roots.get(0);
        WorkbenchDetailView.RepositoryScopeView repositoryScope =
                new WorkbenchDetailView.RepositoryScopeView(
                        root.getRepositoryScopeHash(), root.getPrimaryRepositoryKey(),
                        loadRepositories(root.getId()));
        WorkbenchDetailView.CreationSnapshotView creationSnapshot =
                new WorkbenchDetailView.CreationSnapshotView(
                        root.getCreationSnapshotId(), root.getCreationSnapshotTopologyHash(),
                        root.getCreationSnapshotStateHash(),
                        root.getCreationSnapshotRepositoryCount());
        return Optional.of(new WorkbenchDetailView(
                root.getId(), root.getTitle(), root.getOriginalGoal(), root.getAgentType(),
                root.getEnvironment(), root.getActiveWriteRunId(), root.getStatus(),
                root.getCreatedAt(), root.getUpdatedAt(), root.getVersion(), repositoryScope,
                creationSnapshot, loadPhases(root.getId())));
    }

    private WorkbenchListItemView mapListItem(ResultSet resultSet, int rowNumber)
            throws SQLException {
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

    private WorkbenchRow mapWorkbenchRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new WorkbenchRow(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("original_goal"),
                resultSet.getString("agent_type"),
                resultSet.getString("environment"),
                resultSet.getString("active_write_run_id"),
                resultSet.getString("status"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"),
                resultSet.getLong("version"),
                resultSet.getString("primary_repository_key"),
                resultSet.getString("repository_scope_hash"),
                resultSet.getString("creation_snapshot_id"),
                resultSet.getString("creation_snapshot_topology_hash"),
                resultSet.getString("creation_snapshot_state_hash"),
                resultSet.getInt("creation_snapshot_repository_count"));
    }

    private List<WorkbenchDetailView.RepositoryView> loadRepositories(String workbenchId) {
        return jdbc.query(
                "SELECT repository_key, relative_path, primary_repository "
                        + "FROM workbench_repository_scope WHERE workbench_id = ? "
                        + "ORDER BY primary_repository DESC, repository_key ASC",
                (resultSet, rowNumber) -> new WorkbenchDetailView.RepositoryView(
                        resultSet.getString("repository_key"),
                        resultSet.getString("relative_path"),
                        resultSet.getInt("primary_repository") != 0),
                workbenchId);
    }

    private List<WorkbenchDetailView.PhaseView> loadPhases(String workbenchId) {
        Map<String, List<WorkbenchDetailView.ConversationView>> conversations =
                loadConversations(workbenchId);
        return jdbc.query(
                "SELECT phase, phase_order, status, conversation_generation, active_run_id, "
                        + "active_run_mode, active_run_prepared_at, review_confirmation_id, "
                        + "review_opinion_version, review_opinion_hash, last_activity_at, completed_at "
                        + "FROM workbench_phase WHERE workbench_id = ? ORDER BY phase_order ASC",
                (resultSet, rowNumber) -> mapPhase(resultSet, conversations),
                workbenchId);
    }

    private Map<String, List<WorkbenchDetailView.ConversationView>> loadConversations(
            String workbenchId) {
        List<ConversationRow> rows = jdbc.query(
                "SELECT phase, session_id, generation, created_at, retired_at "
                        + "FROM workbench_phase_conversation WHERE workbench_id = ? "
                        + "ORDER BY phase ASC, generation ASC",
                (resultSet, rowNumber) -> new ConversationRow(
                        resultSet.getString("phase"),
                        new WorkbenchDetailView.ConversationView(
                                resultSet.getString("session_id"),
                                resultSet.getInt("generation"),
                                resultSet.getLong("created_at"),
                                nullableLong(resultSet, "retired_at"))),
                workbenchId);
        Map<String, List<WorkbenchDetailView.ConversationView>> byPhase =
                new HashMap<String, List<WorkbenchDetailView.ConversationView>>();
        for (ConversationRow row : rows) {
            byPhase.computeIfAbsent(row.getPhase(), ignored ->
                    new ArrayList<WorkbenchDetailView.ConversationView>()).add(row.getView());
        }
        return byPhase;
    }

    private WorkbenchDetailView.PhaseView mapPhase(
            ResultSet resultSet,
            Map<String, List<WorkbenchDetailView.ConversationView>> conversations)
            throws SQLException {
        String phase = resultSet.getString("phase");
        int currentGeneration = resultSet.getInt("conversation_generation");
        List<WorkbenchDetailView.ConversationView> allConversations =
                conversations.getOrDefault(phase, Collections.emptyList());
        WorkbenchDetailView.ConversationView currentConversation = null;
        List<WorkbenchDetailView.ConversationView> history =
                new ArrayList<WorkbenchDetailView.ConversationView>();
        for (WorkbenchDetailView.ConversationView conversation : allConversations) {
            if (conversation.getGeneration() == currentGeneration) {
                currentConversation = conversation;
            } else {
                history.add(conversation);
            }
        }
        return new WorkbenchDetailView.PhaseView(
                phase,
                resultSet.getInt("phase_order"),
                resultSet.getString("status"),
                currentGeneration,
                currentConversation,
                history,
                mapActiveRun(resultSet),
                nullableLong(resultSet, "last_activity_at"),
                nullableLong(resultSet, "completed_at"));
    }

    private WorkbenchDetailView.ActiveRunView mapActiveRun(ResultSet resultSet)
            throws SQLException {
        String activeRunId = resultSet.getString("active_run_id");
        if (activeRunId == null) {
            return null;
        }
        return new WorkbenchDetailView.ActiveRunView(
                activeRunId,
                resultSet.getString("active_run_mode"),
                resultSet.getLong("active_run_prepared_at"),
                resultSet.getString("review_confirmation_id"),
                nullableLong(resultSet, "review_opinion_version"),
                resultSet.getString("review_opinion_hash"));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Long.valueOf(value);
    }

    private static String requireIdentity(String value, String name) {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + MAX_ID_LENGTH + " characters");
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
        private final String status;
        private final long createdAt;
        private final long updatedAt;
        private final long version;
        private final String primaryRepositoryKey;
        private final String repositoryScopeHash;
        private final String creationSnapshotId;
        private final String creationSnapshotTopologyHash;
        private final String creationSnapshotStateHash;
        private final int creationSnapshotRepositoryCount;

        private WorkbenchRow(
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
                String primaryRepositoryKey,
                String repositoryScopeHash,
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
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.version = version;
            this.primaryRepositoryKey = primaryRepositoryKey;
            this.repositoryScopeHash = repositoryScopeHash;
            this.creationSnapshotId = creationSnapshotId;
            this.creationSnapshotTopologyHash = creationSnapshotTopologyHash;
            this.creationSnapshotStateHash = creationSnapshotStateHash;
            this.creationSnapshotRepositoryCount = creationSnapshotRepositoryCount;
        }
    }

    @Getter
    private static final class ConversationRow {

        private final String phase;
        private final WorkbenchDetailView.ConversationView view;

        private ConversationRow(
                String phase,
                WorkbenchDetailView.ConversationView view) {
            this.phase = phase;
            this.view = view;
        }
    }
}
