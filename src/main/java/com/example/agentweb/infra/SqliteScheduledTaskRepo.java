package com.example.agentweb.infra;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.schedule.ScheduledTask;
import com.example.agentweb.domain.schedule.ScheduledTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * @author zhourui(V33215020)
 */
@Repository
public class SqliteScheduledTaskRepo implements ScheduledTaskRepository {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUserProvider;

    public SqliteScheduledTaskRepo(JdbcTemplate jdbc, CurrentUserProvider currentUserProvider) {
        this.jdbc = jdbc;
        this.currentUserProvider = currentUserProvider;
    }

    /** {@link #filterUserId()} 的"不过滤"哨兵(后台无登录上下文/调度器加载)，用 == 身份比较。 */
    private static final String NO_FILTER = new String("__NO_FILTER__");

    /**
     * 决定本次查询是否按 user_id 隔离，与 {@code SqliteSessionRepo} 同语义：
     * 返回当前用户 ID → 拼 {@code (user_id IS NULL OR user_id = ?)}；
     * 返回 {@link #NO_FILTER} → 不过滤(后台线程/调度器无上下文，看全部)。
     */
    private String filterUserId() {
        return currentUserProvider.shouldFilter() ? currentUserProvider.currentUserId() : NO_FILTER;
    }

    private static final String COLUMNS =
            "id, name, cron_expr, prompt, working_dir, enabled, "
                    + "created_at, updated_at, last_run_at, last_session_id, user_id";

    private static final RowMapper<ScheduledTask> ROW_MAPPER = new RowMapper<ScheduledTask>() {
        @Override
        public ScheduledTask mapRow(ResultSet rs, int rowNum) throws SQLException {
            String lastRunStr = rs.getString("last_run_at");
            Instant lastRunAt = lastRunStr != null ? Instant.parse(lastRunStr) : null;
            return new ScheduledTask(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("cron_expr"),
                    rs.getString("prompt"),
                    rs.getString("working_dir"),
                    rs.getInt("enabled") == 1,
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("updated_at")),
                    lastRunAt,
                    rs.getString("last_session_id"),
                    rs.getString("user_id")
            );
        }
    };

    @Override
    public void save(ScheduledTask task) {
        jdbc.update(
                "INSERT INTO scheduled_task (id, name, cron_expr, prompt, working_dir, enabled, created_at, updated_at, user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                task.getId(), task.getName(), task.getCronExpr(), task.getPrompt(),
                task.getWorkingDir(),
                task.isEnabled() ? 1 : 0,
                task.getCreatedAt().toString(), task.getUpdatedAt().toString(),
                task.getUserId()
        );
    }

    @Override
    public void update(ScheduledTask task) {
        jdbc.update(
                "UPDATE scheduled_task SET name = ?, cron_expr = ?, prompt = ?, working_dir = ?, enabled = ?, updated_at = ? WHERE id = ?",
                task.getName(), task.getCronExpr(), task.getPrompt(),
                task.getWorkingDir(),
                task.isEnabled() ? 1 : 0,
                Instant.now().toString(), task.getId()
        );
    }

    @Override
    public ScheduledTask findById(String id) {
        String filtered = filterUserId();
        String sql = "SELECT " + COLUMNS + " FROM scheduled_task WHERE id = ?";
        List<Object> args = new ArrayList<>();
        args.add(id);
        if (filtered != NO_FILTER) {
            sql += " AND (user_id IS NULL OR user_id = ?)";
            args.add(filtered);
        }
        List<ScheduledTask> list = jdbc.query(sql, ROW_MAPPER, args.toArray());
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<ScheduledTask> findAll() {
        String filtered = filterUserId();
        String sql = "SELECT " + COLUMNS + " FROM scheduled_task";
        List<Object> args = new ArrayList<>();
        if (filtered != NO_FILTER) {
            sql += " WHERE (user_id IS NULL OR user_id = ?)";
            args.add(filtered);
        }
        sql += " ORDER BY created_at DESC";
        return jdbc.query(sql, ROW_MAPPER, args.toArray());
    }

    @Override
    public List<ScheduledTask> findAllEnabled() {
        // 调度器启动时加载全部启用任务, 不按用户隔离(系统级触发, 须覆盖所有用户的任务)。
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM scheduled_task WHERE enabled = 1 ORDER BY created_at DESC",
                ROW_MAPPER);
    }

    @Override
    public void deleteById(String id) {
        String filtered = filterUserId();
        String sql = "DELETE FROM scheduled_task WHERE id = ?";
        List<Object> args = new ArrayList<>();
        args.add(id);
        if (filtered != NO_FILTER) {
            sql += " AND (user_id IS NULL OR user_id = ?)";
            args.add(filtered);
        }
        jdbc.update(sql, args.toArray());
    }

    @Override
    public void updateLastRun(String id, Instant lastRunAt, String lastSessionId) {
        jdbc.update("UPDATE scheduled_task SET last_run_at = ?, last_session_id = ? WHERE id = ?",
                lastRunAt.toString(), lastSessionId, id);
    }
}
