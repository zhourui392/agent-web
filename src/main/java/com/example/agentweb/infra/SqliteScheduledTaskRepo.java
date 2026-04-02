package com.example.agentweb.infra;

import com.example.agentweb.domain.ScheduledTask;
import com.example.agentweb.domain.ScheduledTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class SqliteScheduledTaskRepo implements ScheduledTaskRepository {

    private final JdbcTemplate jdbc;

    public SqliteScheduledTaskRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
                    rs.getString("last_session_id")
            );
        }
    };

    @Override
    public void save(ScheduledTask task) {
        jdbc.update(
                "INSERT INTO scheduled_task (id, name, cron_expr, prompt, working_dir, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                task.getId(), task.getName(), task.getCronExpr(), task.getPrompt(),
                task.getWorkingDir(),
                task.isEnabled() ? 1 : 0,
                task.getCreatedAt().toString(), task.getUpdatedAt().toString()
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
        List<ScheduledTask> list = jdbc.query(
                "SELECT * FROM scheduled_task WHERE id = ?",
                new Object[]{id}, ROW_MAPPER
        );
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<ScheduledTask> findAll() {
        return jdbc.query("SELECT * FROM scheduled_task ORDER BY created_at DESC", ROW_MAPPER);
    }

    @Override
    public List<ScheduledTask> findAllEnabled() {
        return jdbc.query("SELECT * FROM scheduled_task WHERE enabled = 1 ORDER BY created_at DESC", ROW_MAPPER);
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("DELETE FROM scheduled_task WHERE id = ?", id);
    }

    @Override
    public void updateLastRun(String id, Instant lastRunAt, String lastSessionId) {
        jdbc.update("UPDATE scheduled_task SET last_run_at = ?, last_session_id = ? WHERE id = ?",
                lastRunAt.toString(), lastSessionId, id);
    }
}
