package com.example.agentweb.infra;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
        // WAL 是数据库持久设置，foreign_keys/busy_timeout 同时保护初始化连接。
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.execute("PRAGMA busy_timeout=5000");
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
        migratePhaseHandoffRevisions();
        migrateWorkbenchRunSubmissionProof();
        migrateReviewOpinionContent();
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
        migrateChatSessionKind();
        migrateChatRunOrigin();
        // Migration: drop user suggestion table (建议反馈功能已移除，清理存量表)
        try {
            jdbc.execute("DROP TABLE IF EXISTS user_suggestion");
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
        migrateWorkflowTables();
        migrateHarnessM3();
        migrateHarnessM4();
        createToolInvocationStatisticsIndexes();
    }

    private void migrateChatSessionKind() {
        for (String column : new String[]{
                "session_kind TEXT NOT NULL DEFAULT 'CHAT'",
                "context_id TEXT",
                "retired_at TEXT"}) {
            try {
                jdbc.execute("ALTER TABLE chat_session ADD COLUMN " + column);
            } catch (Exception ignored) {
                // column already exists
            }
        }
        jdbc.update("UPDATE chat_session SET session_kind = 'CHAT' WHERE session_kind IS NULL");
    }

    private void migrateChatRunOrigin() {
        for (String column : new String[]{
                "run_origin TEXT NOT NULL DEFAULT 'CHAT'",
                "origin_reference TEXT",
                "execution_context_id TEXT"}) {
            try {
                jdbc.execute("ALTER TABLE chat_run ADD COLUMN " + column);
            } catch (Exception ignored) {
                // column already exists
            }
        }
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_run_origin_reference "
                + "ON chat_run(run_origin, origin_reference, created_at DESC)");
    }

    private void migratePhaseHandoffRevisions() {
        Integer mismatched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_phase_handoff latest "
                        + "JOIN workbench_phase_handoff_revision revision "
                        + "ON revision.workbench_id=latest.workbench_id "
                        + "AND revision.phase=latest.phase "
                        + "AND revision.version=latest.version "
                        + "WHERE revision.summary<>latest.summary "
                        + "OR revision.decisions_json<>latest.decisions_json "
                        + "OR revision.open_questions_json<>latest.open_questions_json "
                        + "OR revision.pinned_files_json<>latest.pinned_files_json "
                        + "OR revision.referenced_runs_json<>latest.referenced_runs_json "
                        + "OR revision.content_hash<>latest.content_hash "
                        + "OR revision.updated_by_id<>latest.updated_by_id "
                        + "OR revision.updated_by_name<>latest.updated_by_name "
                        + "OR revision.updated_at<>latest.updated_at",
                Integer.class);
        if (mismatched != null && mismatched.intValue() > 0) {
            throw new IllegalStateException(
                    "phase handoff revision migration found conflicting exact revisions");
        }
        jdbc.update("INSERT INTO workbench_phase_handoff_revision ("
                        + "workbench_id, phase, summary, decisions_json, "
                        + "open_questions_json, pinned_files_json, referenced_runs_json, "
                        + "content_hash, updated_by_id, updated_by_name, updated_at, version) "
                        + "SELECT latest.workbench_id, latest.phase, latest.summary, "
                        + "latest.decisions_json, latest.open_questions_json, "
                        + "latest.pinned_files_json, latest.referenced_runs_json, "
                        + "latest.content_hash, latest.updated_by_id, "
                        + "latest.updated_by_name, latest.updated_at, latest.version "
                        + "FROM workbench_phase_handoff latest "
                        + "WHERE NOT EXISTS (SELECT 1 "
                        + "FROM workbench_phase_handoff_revision revision "
                        + "WHERE revision.workbench_id=latest.workbench_id "
                        + "AND revision.phase=latest.phase "
                        + "AND revision.version=latest.version)");
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS "
                + "trg_workbench_handoff_revision_no_update "
                + "BEFORE UPDATE ON workbench_phase_handoff_revision "
                + "BEGIN SELECT RAISE(ABORT, "
                + "'phase handoff revisions are append-only'); END");
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS "
                + "trg_workbench_handoff_revision_no_delete "
                + "BEFORE DELETE ON workbench_phase_handoff_revision "
                + "BEGIN SELECT RAISE(ABORT, "
                + "'phase handoff revisions are append-only'); END");
    }

    private void migrateWorkbenchRunSubmissionProof() {
        addColumnIfMissing("workbench_run_snapshot",
                "submission_idempotency_key TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("workbench_run_snapshot",
                "submission_request_hash TEXT NOT NULL DEFAULT ''");
        List<LegacyWorkbenchRunSubmission> legacy = jdbc.query(
                "SELECT run_id, workbench_id, phase FROM workbench_run_snapshot "
                        + "WHERE submission_idempotency_key='' "
                        + "OR submission_request_hash=''",
                (resultSet, rowNumber) -> new LegacyWorkbenchRunSubmission(
                        resultSet.getString("run_id"),
                        resultSet.getString("workbench_id"),
                        resultSet.getString("phase")));
        for (LegacyWorkbenchRunSubmission row : legacy) {
            int updated = jdbc.update("UPDATE workbench_run_snapshot SET "
                            + "submission_idempotency_key=?, submission_request_hash=? "
                            + "WHERE run_id=? AND (submission_idempotency_key='' "
                            + "OR submission_request_hash='')",
                    legacySubmissionKey(row.runId),
                    legacySubmissionHash(row), row.runId);
            if (updated != 1) {
                throw new IllegalStateException(
                        "legacy workbench run submission proof could not be backfilled");
            }
        }
        Integer invalid = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_run_snapshot WHERE "
                        + "length(trim(submission_idempotency_key)) NOT BETWEEN 1 AND 128 "
                        + "OR length(submission_request_hash)<>64 "
                        + "OR submission_request_hash GLOB '*[^0-9a-f]*'",
                Integer.class);
        if (invalid != null && invalid.intValue() > 0) {
            throw new IllegalStateException(
                    "workbench run submission proof migration found corrupt facts");
        }
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS "
                + "uk_workbench_run_snapshot_phase_submission "
                + "ON workbench_run_snapshot("
                + "workbench_id, phase, submission_idempotency_key)");
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS "
                + "trg_workbench_run_submission_proof_insert "
                + "BEFORE INSERT ON workbench_run_snapshot "
                + "WHEN length(trim(NEW.submission_idempotency_key)) NOT BETWEEN 1 AND 128 "
                + "OR length(NEW.submission_request_hash)<>64 "
                + "OR NEW.submission_request_hash GLOB '*[^0-9a-f]*' "
                + "BEGIN SELECT RAISE(ABORT, "
                + "'invalid workbench run submission proof'); END");
    }

    private void migrateReviewOpinionContent() {
        addColumnIfMissing("workbench_review_opinion",
                "opinion_content TEXT");
    }

    private String legacySubmissionKey(String runId) {
        return "legacy:" + CanonicalHashing.sha256(runId);
    }

    private String legacySubmissionHash(LegacyWorkbenchRunSubmission row) {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(
                canonical, "schema", "workbench-run-legacy-unreplayable@1");
        CanonicalHashing.appendFramed(canonical, "runId", row.runId);
        CanonicalHashing.appendFramed(canonical, "workbenchId", row.workbenchId);
        CanonicalHashing.appendFramed(canonical, "phase", row.phase);
        return CanonicalHashing.sha256(canonical.toString());
    }

    private void createToolInvocationStatisticsIndexes() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_started "
                + "ON chat_tool_invocation(started_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_source_started "
                + "ON chat_tool_invocation(source, started_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_kind_started "
                + "ON chat_tool_invocation(invocation_kind, started_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_status_started "
                + "ON chat_tool_invocation(status, started_at)");
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

    private void migrateHarnessM3() {
        addColumnIfMissing("harness_stage_attempt", "snapshot_hash TEXT");
        addColumnIfMissing("harness_stage_attempt", "execution_id TEXT");
        addColumnIfMissing("harness_capability_snapshot",
                "schema_version TEXT NOT NULL DEFAULT 'M2'");
        addColumnIfMissing("harness_capability_snapshot",
                "selected_mcp_servers_json TEXT NOT NULL DEFAULT '[]'");
        addColumnIfMissing("harness_capability_snapshot",
                "rejected_mcp_servers_json TEXT NOT NULL DEFAULT '[]'");
        addColumnIfMissing("harness_capability_snapshot", "runtime_enforcement_json TEXT");
        addColumnIfMissing("harness_capability_snapshot",
                "workspace_runtime_inventory_json TEXT NOT NULL DEFAULT '{}'");
    }

    private void migrateHarnessM4() {
        addColumnIfMissing("harness_run",
                "repository_root TEXT NOT NULL DEFAULT 'UNKNOWN'");
        addColumnIfMissing("harness_run", "git_branch TEXT NOT NULL DEFAULT 'UNKNOWN'");
        addColumnIfMissing("harness_run",
                "git_head TEXT NOT NULL DEFAULT '0000000000000000000000000000000000000000'");
        addColumnIfMissing("harness_run", "git_clean INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("harness_run", "git_diff_hash TEXT NOT NULL DEFAULT '"
                + "0000000000000000000000000000000000000000000000000000000000000000'");
        addColumnIfMissing("harness_run", "git_captured_at INTEGER NOT NULL DEFAULT 0");
    }

    private void addColumnIfMissing(String table, String definition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
        } catch (Exception ignored) {
            // SQLite 没有 ADD COLUMN IF NOT EXISTS；重复初始化时已存在即视为成功。
        }
    }

    private static final class LegacyWorkbenchRunSubmission {

        private final String runId;
        private final String workbenchId;
        private final String phase;

        private LegacyWorkbenchRunSubmission(
                String runId, String workbenchId, String phase) {
            this.runId = runId;
            this.workbenchId = workbenchId;
            this.phase = phase;
        }
    }
}
