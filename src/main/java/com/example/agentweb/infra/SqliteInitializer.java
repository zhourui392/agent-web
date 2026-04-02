package com.example.agentweb.infra;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Component
public class SqliteInitializer {

    private final JdbcTemplate jdbc;

    public SqliteInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() throws Exception {
        String sql = StreamUtils.copyToString(
                new ClassPathResource("schema.sql").getInputStream(),
                StandardCharsets.UTF_8
        );
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
        // Migration: add resume_id column for existing databases
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN resume_id TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration: add title column for explicit session titles
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN title TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration: remove agent_type column from scheduled_task
        migrateScheduledTaskDropAgentType();
    }

    private void migrateScheduledTaskDropAgentType() {
        try {
            // Check if agent_type column exists
            jdbc.queryForList("SELECT agent_type FROM scheduled_task LIMIT 1");
        } catch (Exception e) {
            // Column doesn't exist, no migration needed
            return;
        }
        jdbc.execute("CREATE TABLE scheduled_task_new ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, cron_expr TEXT NOT NULL, "
                + "prompt TEXT NOT NULL, working_dir TEXT NOT NULL, "
                + "enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, "
                + "updated_at TEXT NOT NULL, last_run_at TEXT, last_session_id TEXT)");
        jdbc.execute("INSERT INTO scheduled_task_new "
                + "SELECT id, name, cron_expr, prompt, working_dir, enabled, "
                + "created_at, updated_at, last_run_at, last_session_id FROM scheduled_task");
        jdbc.execute("DROP TABLE scheduled_task");
        jdbc.execute("ALTER TABLE scheduled_task_new RENAME TO scheduled_task");
    }
}
