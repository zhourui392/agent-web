package com.example.agentweb.infra.workbench.query;

import com.example.agentweb.app.workbench.run.WorkbenchRunDetailView;
import com.example.agentweb.app.workbench.run.WorkbenchRunHistoryQuery;
import com.example.agentweb.app.workbench.run.WorkbenchRunListCursor;
import com.example.agentweb.app.workbench.run.WorkbenchRunListItemView;
import com.example.agentweb.app.workbench.run.WorkbenchRunListPage;
import com.example.agentweb.app.workbench.run.WorkbenchRunListRequest;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchId;
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
 * SQLite Workbench Run 历史 CQRS 投影。
 *
 * <p>查询要求 Workbench、不可变 Snapshot、ChatRun 来源引用和 Stage
 * Conversation 同时匹配；任何损坏或跨 Workbench 绑定均不会进入读模型。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@Transactional(readOnly = true)
public class SqliteWorkbenchRunHistoryQuery
        implements WorkbenchRunHistoryQuery {

    private static final String EXACT_RUN_FROM =
            " FROM workbench_stage_run_snapshot s "
                    + "JOIN workbench w ON w.id=s.workbench_id "
                    + "AND w.repository_scope_hash=s.repository_scope_hash "
                    + "JOIN chat_run r ON r.id=s.run_id "
                    + "AND r.run_origin='WORKBENCH' "
                    + "AND r.origin_reference=(s.workbench_id || ':' || "
                    + "s.stage_instance_identifier) "
                    + "AND r.execution_context_id=s.run_id "
                    + "JOIN workbench_stage_conversation c "
                    + "ON c.workbench_id=s.workbench_id "
                    + "AND c.stage_instance_identifier="
                    + "s.stage_instance_identifier "
                    + "AND c.session_id=r.session_id "
                    + "JOIN chat_session cs ON cs.id=r.session_id "
                    + "AND cs.session_kind='WORKBENCH_STAGE' "
                    + "AND cs.context_id=(s.workbench_id || ':' || "
                    + "s.stage_instance_identifier) "
                    + "AND cs.user_id=w.owner_id "
                    + "AND cs.user_name=w.owner_name ";

    private static final String LIST_COLUMNS =
            "SELECT r.id AS run_id, s.workbench_id, "
                    + "s.stage_instance_identifier, "
                    + "r.session_id, r.status, s.run_mode, "
                    + "r.last_event_seq, r.created_at, r.started_at, "
                    + "r.finished_at, r.failure_code ";

    private static final String DETAIL_COLUMNS =
            "SELECT r.id AS run_id, s.workbench_id, "
                    + "s.stage_instance_identifier, "
                    + "r.session_id, r.status, s.run_mode, "
                    + "r.last_event_seq, COALESCE((SELECT MIN(e.seq) "
                    + "FROM chat_run_event e WHERE e.run_id=r.id), "
                    + "CASE WHEN r.last_event_seq>0 THEN r.last_event_seq+1 "
                    + "ELSE 0 END) "
                    + "AS earliest_retained_seq, r.created_at, r.started_at, "
                    + "r.finished_at, r.exit_code, r.failure_code, "
                    + "s.capability_snapshot_hash, s.repository_scope_hash ";

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchRunHistoryQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public WorkbenchRunListPage list(
            WorkbenchId workbenchId, String repositoryScopeHash,
            WorkbenchRunListRequest request) {
        Objects.requireNonNull(workbenchId, "workbenchId");
        requireScopeHash(repositoryScopeHash);
        Objects.requireNonNull(request, "request");
        StringBuilder sql = new StringBuilder(LIST_COLUMNS)
                .append(EXACT_RUN_FROM)
                .append("WHERE s.workbench_id=? AND s.repository_scope_hash=?");
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(workbenchId.getValue());
        arguments.add(repositoryScopeHash);
        if (request.getStageInstanceIdentifier() != null) {
            sql.append(" AND s.stage_instance_identifier=?");
            arguments.add(request.getStageInstanceIdentifier());
        }
        WorkbenchRunListCursor cursor = request.getCursor();
        if (cursor != null) {
            sql.append(" AND (r.created_at<? OR "
                    + "(r.created_at=? AND r.id<?))");
            arguments.add(cursor.getCreatedAt());
            arguments.add(cursor.getCreatedAt());
            arguments.add(cursor.getRunId());
        }
        sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ?");
        arguments.add(request.getLimit() + 1);

        List<WorkbenchRunListItemView> fetched = jdbc.query(
                sql.toString(), this::mapListItem,
                arguments.toArray());
        boolean hasNext = fetched.size() > request.getLimit();
        List<WorkbenchRunListItemView> items = hasNext
                ? new ArrayList<WorkbenchRunListItemView>(
                        fetched.subList(0, request.getLimit()))
                : fetched;
        WorkbenchRunListCursor nextCursor = null;
        if (hasNext) {
            WorkbenchRunListItemView last =
                    items.get(items.size() - 1);
            nextCursor = new WorkbenchRunListCursor(
                    last.getCreatedAt(), last.getRunId());
        }
        return new WorkbenchRunListPage(items, nextCursor);
    }

    @Override
    public Optional<WorkbenchRunDetailView> findDetail(
            WorkbenchId workbenchId, String repositoryScopeHash,
            String runId) {
        Objects.requireNonNull(workbenchId, "workbenchId");
        requireScopeHash(repositoryScopeHash);
        requireRunId(runId);
        List<WorkbenchRunDetailView> rows = jdbc.query(
                DETAIL_COLUMNS + EXACT_RUN_FROM
                        + "WHERE s.workbench_id=? "
                        + "AND s.repository_scope_hash=? AND s.run_id=?",
                this::mapDetail, workbenchId.getValue(),
                repositoryScopeHash, runId);
        return rows.isEmpty()
                ? Optional.empty() : Optional.of(rows.get(0));
    }

    private WorkbenchRunListItemView mapListItem(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkbenchRunListItemView(
                resultSet.getString("run_id"),
                resultSet.getString("workbench_id"),
                resultSet.getString("stage_instance_identifier"),
                resultSet.getString("session_id"),
                ChatRunStatus.valueOf(resultSet.getString("status")),
                RunMode.valueOf(resultSet.getString("run_mode")),
                resultSet.getLong("last_event_seq"),
                resultSet.getLong("created_at"),
                nullableLong(resultSet, "started_at"),
                nullableLong(resultSet, "finished_at"),
                resultSet.getString("failure_code"));
    }

    private WorkbenchRunDetailView mapDetail(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkbenchRunDetailView(
                resultSet.getString("run_id"),
                resultSet.getString("workbench_id"),
                resultSet.getString("stage_instance_identifier"),
                resultSet.getString("session_id"),
                ChatRunStatus.valueOf(resultSet.getString("status")),
                RunMode.valueOf(resultSet.getString("run_mode")),
                resultSet.getLong("last_event_seq"),
                resultSet.getLong("earliest_retained_seq"),
                resultSet.getLong("created_at"),
                nullableLong(resultSet, "started_at"),
                nullableLong(resultSet, "finished_at"),
                nullableInteger(resultSet, "exit_code"),
                resultSet.getString("failure_code"),
                resultSet.getString("capability_snapshot_hash"),
                resultSet.getString("repository_scope_hash"));
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

    private void requireScopeHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "repository scope hash must be lowercase SHA-256");
        }
    }

    private void requireRunId(String value) {
        if (value == null || value.trim().isEmpty()
                || value.length() > 128) {
            throw new IllegalArgumentException(
                    "workbench run id must contain 1 to 128 characters");
        }
    }
}
