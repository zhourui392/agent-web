package com.example.agentweb.infra.workbench.admin;

import com.example.agentweb.app.workbench.admin.AdminWorkbenchDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListCursor;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListItemView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListPage;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListRequest;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchQueryService;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListCursor;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListItemView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListPage;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListRequest;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.RunMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 独立 Admin Workbench/Run SQLite 安全投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@Transactional(readOnly = true)
public class SqliteAdminWorkbenchQueryService
        implements AdminWorkbenchQueryService {

    private static final String EXACT_RUN_FROM =
            "FROM workbench_stage_run_snapshot s "
                    + "JOIN chat_run r ON r.id=s.run_id "
                    + "AND r.run_origin='WORKBENCH' "
                    + "AND r.origin_reference=s.workbench_id || ':' "
                    + "|| s.stage_instance_identifier "
                    + "AND r.execution_context_id=s.run_id ";

    private final JdbcTemplate jdbc;

    public SqliteAdminWorkbenchQueryService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AdminWorkbenchListPage list(
            AdminWorkbenchListRequest request) {
        Objects.requireNonNull(request, "request");
        StringBuilder sql = new StringBuilder(
                "SELECT w.id, w.owner_id, w.owner_name, w.title, "
                        + "w.status, w.agent_type, w.environment, "
                        + "w.primary_repository_key, w.active_write_run_id, "
                        + "w.created_at, w.updated_at, w.version, "
                        + "(SELECT COUNT(*) FROM workbench_repository_scope scope "
                        + "WHERE scope.workbench_id=w.id) repository_count "
                        + "FROM workbench w WHERE 1=1");
        List<Object> arguments = new ArrayList<Object>();
        if (request.getStatus() != null) {
            sql.append(" AND w.status=?");
            arguments.add(request.getStatus().name());
        }
        AdminWorkbenchListCursor cursor = request.getCursor();
        if (cursor != null) {
            sql.append(" AND (w.updated_at<? OR "
                    + "(w.updated_at=? AND w.id<?))");
            arguments.add(Long.valueOf(cursor.getUpdatedAt()));
            arguments.add(Long.valueOf(cursor.getUpdatedAt()));
            arguments.add(cursor.getWorkbenchId());
        }
        sql.append(" ORDER BY w.updated_at DESC, w.id DESC LIMIT ?");
        arguments.add(Integer.valueOf(request.getLimit() + 1));
        List<AdminWorkbenchListItemView> fetched = jdbc.query(
                sql.toString(), this::mapWorkbenchListItem,
                arguments.toArray());
        boolean hasNext = fetched.size() > request.getLimit();
        List<AdminWorkbenchListItemView> items = hasNext
                ? new ArrayList<AdminWorkbenchListItemView>(
                fetched.subList(0, request.getLimit())) : fetched;
        AdminWorkbenchListCursor next = null;
        if (hasNext) {
            AdminWorkbenchListItemView last = items.get(items.size() - 1);
            next = new AdminWorkbenchListCursor(
                    last.getUpdatedAt(), last.getWorkbenchId());
        }
        return new AdminWorkbenchListPage(items, next);
    }

    @Override
    public Optional<AdminWorkbenchDetailView> findDetail(
            String workbenchId) {
        String id = requireId(workbenchId, "workbenchId");
        List<AdminWorkbenchDetailView> rows = jdbc.query(
                "SELECT id, owner_id, owner_name, title, status, agent_type, "
                        + "environment, primary_repository_key, "
                        + "repository_scope_hash, active_write_run_id, "
                        + "created_at, updated_at, version FROM workbench WHERE id=?",
                (resultSet, rowNumber) -> new AdminWorkbenchDetailView(
                        resultSet.getString("id"),
                        resultSet.getString("owner_id"),
                        resultSet.getString("owner_name"),
                        resultSet.getString("title"),
                        resultSet.getString("status"),
                        resultSet.getString("agent_type"),
                        resultSet.getString("environment"),
                        resultSet.getString("primary_repository_key"),
                        resultSet.getString("repository_scope_hash"),
                        resultSet.getString("active_write_run_id"),
                        resultSet.getLong("created_at"),
                        resultSet.getLong("updated_at"),
                        resultSet.getLong("version"),
                        loadRepositories(id), loadStages(id)),
                id);
        return rows.isEmpty()
                ? Optional.<AdminWorkbenchDetailView>empty()
                : Optional.of(rows.get(0));
    }

    @Override
    public AdminWorkbenchRunListPage listRuns(
            String workbenchId, AdminWorkbenchRunListRequest request) {
        String id = requireId(workbenchId, "workbenchId");
        Objects.requireNonNull(request, "request");
        StringBuilder sql = new StringBuilder(
                "SELECT r.id run_id, s.workbench_id, "
                        + "s.stage_instance_identifier, r.status, "
                        + "s.run_mode, r.last_event_seq, r.created_at, "
                        + "r.started_at, r.cancel_requested_at, r.finished_at, "
                        + "r.failure_code ")
                .append(EXACT_RUN_FROM)
                .append("WHERE s.workbench_id=?");
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(id);
        if (request.getStatus() != null) {
            sql.append(" AND r.status=?");
            arguments.add(request.getStatus().name());
        }
        AdminWorkbenchRunListCursor cursor = request.getCursor();
        if (cursor != null) {
            sql.append(" AND (r.created_at<? OR "
                    + "(r.created_at=? AND r.id<?))");
            arguments.add(Long.valueOf(cursor.getCreatedAt()));
            arguments.add(Long.valueOf(cursor.getCreatedAt()));
            arguments.add(cursor.getRunId());
        }
        sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ?");
        arguments.add(Integer.valueOf(request.getLimit() + 1));
        List<AdminWorkbenchRunListItemView> fetched = jdbc.query(
                sql.toString(), this::mapRunListItem,
                arguments.toArray());
        boolean hasNext = fetched.size() > request.getLimit();
        List<AdminWorkbenchRunListItemView> items = hasNext
                ? new ArrayList<AdminWorkbenchRunListItemView>(
                fetched.subList(0, request.getLimit())) : fetched;
        AdminWorkbenchRunListCursor next = null;
        if (hasNext) {
            AdminWorkbenchRunListItemView last = items.get(items.size() - 1);
            next = new AdminWorkbenchRunListCursor(
                    last.getCreatedAt(), last.getRunId());
        }
        return new AdminWorkbenchRunListPage(items, next);
    }

    @Override
    public Optional<AdminWorkbenchRunDetailView> findRunDetail(
            String workbenchId, String runId) {
        String id = requireId(workbenchId, "workbenchId");
        String validRunId = requireId(runId, "runId");
        List<AdminWorkbenchRunDetailView> rows = jdbc.query(
                "SELECT r.id run_id, s.workbench_id, "
                        + "s.stage_instance_identifier, r.status, "
                        + "s.run_mode, r.last_event_seq, r.created_at, "
                        + "r.started_at, r.cancel_requested_at, r.finished_at, "
                        + "r.exit_code, r.failure_code, s.repository_scope_hash, "
                        + "s.capability_snapshot_hash, s.prompt_hash, "
                        + "EXISTS(SELECT 1 FROM chat_run_runtime_handle h "
                        + "WHERE h.run_id=r.id) runtime_handle_present "
                        + EXACT_RUN_FROM
                        + "WHERE s.workbench_id=? AND s.run_id=?",
                this::mapRunDetail, id, validRunId);
        return rows.isEmpty()
                ? Optional.<AdminWorkbenchRunDetailView>empty()
                : Optional.of(rows.get(0));
    }

    private AdminWorkbenchListItemView mapWorkbenchListItem(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new AdminWorkbenchListItemView(
                resultSet.getString("id"),
                resultSet.getString("owner_id"),
                resultSet.getString("owner_name"),
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

    private List<AdminWorkbenchDetailView.RepositoryView> loadRepositories(
            String workbenchId) {
        return jdbc.query(
                "SELECT repository_key, relative_path, primary_repository "
                        + "FROM workbench_repository_scope WHERE workbench_id=? "
                        + "ORDER BY primary_repository DESC, repository_key ASC",
                (resultSet, rowNumber) ->
                        new AdminWorkbenchDetailView.RepositoryView(
                                resultSet.getString("repository_key"),
                                resultSet.getString("relative_path"),
                                resultSet.getInt("primary_repository") != 0),
                workbenchId);
    }

    private List<AdminWorkbenchDetailView.StageView> loadStages(
            String workbenchId) {
        return jdbc.query(
                "SELECT stage_instance_identifier, definition_identifier, "
                        + "definition_revision, sequence_number, status, "
                        + "active_run_id, "
                        + "active_run_mode, last_activity_at, completed_at "
                        + "FROM workbench_stage WHERE workbench_id=? "
                        + "ORDER BY sequence_number ASC",
                (resultSet, rowNumber) ->
                        new AdminWorkbenchDetailView.StageView(
                                resultSet.getString(
                                        "stage_instance_identifier"),
                                resultSet.getString(
                                        "definition_identifier"),
                                resultSet.getLong("definition_revision"),
                                resultSet.getInt("sequence_number"),
                                resultSet.getString("status"),
                                resultSet.getString("active_run_id"),
                                resultSet.getString("active_run_mode"),
                                nullableLong(resultSet, "last_activity_at"),
                                nullableLong(resultSet, "completed_at")),
                workbenchId);
    }

    private AdminWorkbenchRunListItemView mapRunListItem(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new AdminWorkbenchRunListItemView(
                resultSet.getString("run_id"),
                resultSet.getString("workbench_id"),
                resultSet.getString("stage_instance_identifier"),
                ChatRunStatus.valueOf(resultSet.getString("status")),
                RunMode.valueOf(resultSet.getString("run_mode")),
                resultSet.getLong("last_event_seq"),
                resultSet.getLong("created_at"),
                nullableLong(resultSet, "started_at"),
                nullableLong(resultSet, "cancel_requested_at"),
                nullableLong(resultSet, "finished_at"),
                resultSet.getString("failure_code"));
    }

    private AdminWorkbenchRunDetailView mapRunDetail(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new AdminWorkbenchRunDetailView(
                resultSet.getString("run_id"),
                resultSet.getString("workbench_id"),
                resultSet.getString("stage_instance_identifier"),
                ChatRunStatus.valueOf(resultSet.getString("status")),
                RunMode.valueOf(resultSet.getString("run_mode")),
                resultSet.getLong("last_event_seq"),
                resultSet.getLong("created_at"),
                nullableLong(resultSet, "started_at"),
                nullableLong(resultSet, "cancel_requested_at"),
                nullableLong(resultSet, "finished_at"),
                nullableInteger(resultSet, "exit_code"),
                resultSet.getString("failure_code"),
                resultSet.getString("repository_scope_hash"),
                resultSet.getString("capability_snapshot_hash"),
                resultSet.getString("prompt_hash"),
                resultSet.getInt("runtime_handle_present") != 0);
    }

    private Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Long.valueOf(value);
    }

    private Integer nullableInteger(ResultSet resultSet, String column)
            throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : Integer.valueOf(value);
    }

    private String requireId(String value, String name) {
        if (value == null || value.trim().isEmpty()
                || value.length() > 128) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
