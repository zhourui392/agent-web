package com.example.agentweb.infra;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * @author zhourui(V33215020)
 */
@Component
public class SqliteInitializer {

    private static final String SQL_STATEMENT_DELIMITER = ";";

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
        for (String statement : sql.split(SQL_STATEMENT_DELIMITER)) {
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
        // Migration: add share_token column for session sharing
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN share_token TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        try {
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_session_share_token ON chat_session(share_token)");
        } catch (Exception ignored) {
        }
        // Migration: add env column so chat sessions can be resumed with their original environment
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN env TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration: add feedback columns so users can rate AI analysis correctness per session
        for (String column : new String[]{"feedback_rating TEXT", "feedback_comment TEXT", "feedback_at TEXT"}) {
            try {
                jdbc.execute("ALTER TABLE chat_session ADD COLUMN " + column);
            } catch (Exception ignored) {
                // column already exists
            }
        }
        // Migration: add last_message_at (epoch millis) so refinery scheduler can detect 静默 sessions without JOIN
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN last_message_at INTEGER");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration: add client_ip so chat sessions record originating client IP for audit attribution
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN client_ip TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration: add user_id so chat sessions are isolated per login user (NULL = legacy/system, visible to all)
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN user_id TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_session_user_id ON chat_session(user_id)");
        } catch (Exception ignored) {
        }
        // Migration: add user_name to record the creator's display name (e.g. 周锐) for audit only
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN user_name TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration: create user suggestion table for chat page feedback/suggestion tickets
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS user_suggestion ("
                    + "id TEXT PRIMARY KEY, "
                    + "user_id TEXT, "
                    + "user_name TEXT, "
                    + "title TEXT, "
                    + "content TEXT NOT NULL, "
                    + "contact TEXT, "
                    + "status TEXT NOT NULL, "
                    + "admin_reply TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL, "
                    + "replied_at INTEGER)");
        } catch (Exception ignored) {
        }
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_user_suggestion_user "
                    + "ON user_suggestion(user_id, updated_at)");
        } catch (Exception ignored) {
        }
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_user_suggestion_status "
                    + "ON user_suggestion(status, updated_at)");
        } catch (Exception ignored) {
        }
        // Migration: remove agent_type column from scheduled_task
        migrateScheduledTaskDropAgentType();
        // Migration: add user_id so scheduled tasks are isolated per user, and executed
        // conversations are attributed to the task owner. 必须在 dropAgentType 重建表之后执行。
        migrateScheduledTaskAddUserId();
        // Migration: add retry_count on chat_session_rag_state so refinery scheduler can cap
        // retries on deterministic failures (避免确定性失败的会话每轮白烧 CLI 钉死调度窗口)
        try {
            jdbc.execute("ALTER TABLE chat_session_rag_state ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration (Knowledge Refinery Phase 1.3): polymorphic source/tier/env on chat_rag_chunk.
        // 历史 chunk 全部默认 CHAT / EXPLORATORY / unknown, 与现有召回行为兼容 (chat 召回不带 tier 过滤).
        for (String column : new String[]{
                "source_type TEXT NOT NULL DEFAULT 'CHAT'",
                "tier        TEXT NOT NULL DEFAULT 'EXPLORATORY'",
                "env         TEXT NOT NULL DEFAULT 'unknown'"}) {
            try {
                jdbc.execute("ALTER TABLE chat_rag_chunk ADD COLUMN " + column);
            } catch (Exception ignored) {
                // column already exists
            }
        }
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_rag_chunk_source ON chat_rag_chunk(source_type, tier)");
        } catch (Exception ignored) {
        }
        // Migration (召回治理 PR-1): 正文原文路径, 指针注入时优先用 issue-log 等已审核文件
        try {
            jdbc.execute("ALTER TABLE chat_rag_chunk ADD COLUMN detail_path TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration (召回治理 PR-5): 归档原因, 区分反例隔离(NEGATIVE_VERDICT)与自然过期(TTL_EXPIRED)
        try {
            jdbc.execute("ALTER TABLE chat_rag_chunk ADD COLUMN archive_reason TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        // Migration (M4 知识轴): 触发场景描述(参与 embed 文本) + 注入/采纳遥测两列.
        // 历史 chunk 描述为 NULL(读侧归一化为空串), 计数从 0 起——重嵌入经管理台 /api/refinery/reembed 渐进迁移
        for (String column : new String[]{
                "trigger_description TEXT",
                "inject_count INTEGER NOT NULL DEFAULT 0",
                "adopt_count  INTEGER NOT NULL DEFAULT 0"}) {
            try {
                jdbc.execute("ALTER TABLE chat_rag_chunk ADD COLUMN " + column);
            } catch (Exception ignored) {
                // column already exists
            }
        }
        migrateChatRecallObservation();
        // Migration (M1 需求绑定): 挂靠需求的 run/会话回链 requirement_id（可空，NULL=非需求线）
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN requirement_id TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        migrateWorkflowTables();
    }

    private void migrateChatRecallObservation() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS chat_recall_attempt ("
                    + "id TEXT PRIMARY KEY, "
                    + "session_id TEXT NOT NULL, "
                    + "user_message_id INTEGER NOT NULL, "
                    + "assistant_message_id INTEGER, "
                    + "query TEXT NOT NULL, "
                    + "recall_enabled INTEGER NOT NULL, "
                    + "env TEXT, "
                    + "status TEXT NOT NULL, "
                    + "skip_reason TEXT, "
                    + "hit_count INTEGER NOT NULL DEFAULT 0, "
                    + "top_k INTEGER, "
                    + "active_count INTEGER, "
                    + "filtered_count INTEGER, "
                    + "below_vector_floor INTEGER, "
                    + "bad_vector_count INTEGER, "
                    + "ranked_count INTEGER, "
                    + "top_vector_score REAL, "
                    + "top_final_score REAL, "
                    + "params_json TEXT, "
                    + "embedding_model TEXT, "
                    + "embedding_dimension INTEGER, "
                    + "latency_ms INTEGER, "
                    + "error_type TEXT, "
                    + "error_message TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_session "
                    + "ON chat_recall_attempt(session_id, created_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_status "
                    + "ON chat_recall_attempt(status, created_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_created "
                    + "ON chat_recall_attempt(created_at)");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_recall_attempt_user_msg "
                    + "ON chat_recall_attempt(user_message_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_model "
                    + "ON chat_recall_attempt(embedding_model, created_at)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS chat_recall_hit ("
                    + "attempt_id TEXT NOT NULL, "
                    + "rank_no INTEGER NOT NULL, "
                    + "chunk_id TEXT NOT NULL, "
                    + "source_session_id TEXT, "
                    + "source_msg_range TEXT, "
                    + "title TEXT, "
                    + "conclusion TEXT, "
                    + "final_score REAL, "
                    + "vector_score REAL, "
                    + "signal_score REAL, "
                    + "time_score REAL, "
                    + "embedding_model TEXT, "
                    + "source_type TEXT, "
                    + "tier TEXT, "
                    + "env TEXT, "
                    + "chunk_score REAL, "
                    + "chunk_created_at INTEGER, "
                    + "created_at INTEGER NOT NULL, "
                    + "PRIMARY KEY (attempt_id, rank_no))");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_recall_hit_chunk "
                    + "ON chat_recall_hit(chunk_id)");
        } catch (Exception ignored) {
        }
    }

    /**
     * 给 scheduled_task 增加 user_id 列。历史行保留 {@code null}，按系统任务语义兼容读取；
     * 新建任务在应用服务中记录当前登录用户。
     */
    private void migrateScheduledTaskAddUserId() {
        try {
            jdbc.execute("ALTER TABLE scheduled_task ADD COLUMN user_id TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_scheduled_task_user_id ON scheduled_task(user_id)");
        } catch (Exception ignored) {
        }
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

    private void migrateWorkflowTables() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_definition ("
                    + "id TEXT PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "description TEXT, "
                    + "agent_type TEXT NOT NULL, "
                    + "working_dir TEXT NOT NULL, "
                    + "steps_json TEXT NOT NULL, "
                    + "enabled INTEGER NOT NULL DEFAULT 1, "
                    + "created_by TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workflow_definition_created "
                    + "ON workflow_definition(created_at)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_execution ("
                    + "id TEXT PRIMARY KEY, "
                    + "workflow_id TEXT NOT NULL, "
                    + "status TEXT NOT NULL, "
                    + "inputs_json TEXT, "
                    + "started_at INTEGER NOT NULL, "
                    + "finished_at INTEGER, "
                    + "error_message TEXT, "
                    + "created_by TEXT)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workflow_execution_workflow "
                    + "ON workflow_execution(workflow_id, started_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workflow_execution_status "
                    + "ON workflow_execution(status)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_step_execution ("
                    + "id TEXT PRIMARY KEY, "
                    + "execution_id TEXT NOT NULL, "
                    + "step_index INTEGER NOT NULL, "
                    + "step_name TEXT NOT NULL, "
                    + "status TEXT NOT NULL, "
                    + "prompt TEXT NOT NULL, "
                    + "output TEXT, "
                    + "error_message TEXT, "
                    + "started_at INTEGER NOT NULL, "
                    + "finished_at INTEGER)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workflow_step_execution_execution "
                    + "ON workflow_step_execution(execution_id, step_index)");
        } catch (Exception ignored) {
            // 建表迁移失败交给后续仓储访问暴露真实错误,避免启动因老库单点脏状态直接中断。
        }
    }
}
