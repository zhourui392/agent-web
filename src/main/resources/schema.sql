CREATE TABLE IF NOT EXISTS chat_session (
    id               TEXT PRIMARY KEY,
    agent_type       TEXT    NOT NULL,
    working_dir      TEXT    NOT NULL,
    created_at       TEXT    NOT NULL,
    resume_id        TEXT,
    share_token      TEXT,
    env              TEXT,
    feedback_rating  TEXT,
    feedback_comment TEXT,
    feedback_at      TEXT,
    last_message_at  INTEGER,
    client_ip        TEXT,
    session_kind     TEXT    NOT NULL DEFAULT 'CHAT',
    context_id       TEXT,
    retired_at       TEXT,
    CHECK ((session_kind = 'CHAT' AND context_id IS NULL AND retired_at IS NULL)
        OR (session_kind = 'WORKBENCH_PHASE'
            AND context_id IS NOT NULL AND length(trim(context_id)) > 0)),
    CHECK (retired_at IS NULL OR retired_at >= created_at)
);

CREATE TABLE IF NOT EXISTS chat_message (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    role        TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    timestamp   TEXT    NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chat_session(id)
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message(session_id);

-- 一次聊天执行的独立生命周期。recall_enabled 是提交时快照，后台线程不再信任浏览器状态。
CREATE TABLE IF NOT EXISTS chat_run (
    id                    TEXT PRIMARY KEY,
    session_id            TEXT    NOT NULL,
    user_message_id       INTEGER NOT NULL,
    assistant_message_id  INTEGER,
    idempotency_key       TEXT    NOT NULL,
    recall_enabled        INTEGER NOT NULL DEFAULT 1,
    run_origin            TEXT    NOT NULL DEFAULT 'CHAT',
    origin_reference      TEXT,
    execution_context_id  TEXT,
    status                TEXT    NOT NULL,
    last_event_seq        INTEGER NOT NULL DEFAULT 0,
    exit_code             INTEGER,
    failure_code          TEXT,
    error_message         TEXT,
    created_at            INTEGER NOT NULL,
    started_at            INTEGER,
    cancel_requested_at   INTEGER,
    finished_at           INTEGER,
    updated_at            INTEGER NOT NULL,
    version               INTEGER NOT NULL DEFAULT 0,
    UNIQUE (session_id, idempotency_key),
    UNIQUE (assistant_message_id),
    CHECK (recall_enabled IN (0, 1)),
    CHECK ((run_origin = 'CHAT' AND origin_reference IS NULL
            AND execution_context_id IS NULL)
        OR (run_origin = 'WORKBENCH' AND origin_reference IS NOT NULL
            AND execution_context_id IS NOT NULL)),
    CHECK (last_event_seq >= 0),
    CHECK (status IN (
        'PENDING', 'RUNNING', 'CANCEL_REQUESTED',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'
    ))
);

CREATE INDEX IF NOT EXISTS idx_chat_run_session_created
    ON chat_run(session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_run_status_updated
    ON chat_run(status, updated_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_run_active_session
    ON chat_run(session_id)
    WHERE status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED');

-- ChatRun 与公共 Runtime Handle 的稳定绑定，供停止与重启恢复对账。
CREATE TABLE IF NOT EXISTS chat_run_runtime_handle (
    run_id        TEXT PRIMARY KEY,
    execution_id  TEXT NOT NULL UNIQUE,
    handle_id     TEXT NOT NULL UNIQUE,
    bound_at      INTEGER NOT NULL,
    CHECK (run_id = execution_id),
    FOREIGN KEY (run_id) REFERENCES chat_run(id) ON DELETE CASCADE
);

-- NATIVE 诊断状态按成功 ChatRun 的消息边界追加，不能用 session 级可变 latest 行。
CREATE TABLE IF NOT EXISTS native_diagnosis_checkpoint (
    run_id                   TEXT PRIMARY KEY,
    session_id               TEXT    NOT NULL,
    user_message_id          INTEGER NOT NULL,
    assistant_message_id     INTEGER NOT NULL,
    state_snapshot           TEXT    NOT NULL,
    snapshot_schema_version  TEXT,
    input_tokens             INTEGER NOT NULL DEFAULT 0,
    output_tokens            INTEGER NOT NULL DEFAULT 0,
    cache_read_input_tokens  INTEGER NOT NULL DEFAULT 0,
    created_at               INTEGER NOT NULL,
    UNIQUE (assistant_message_id),
    CHECK (input_tokens >= 0),
    CHECK (output_tokens >= 0),
    CHECK (cache_read_input_tokens >= 0)
);

CREATE INDEX IF NOT EXISTS idx_native_checkpoint_session_boundary
    ON native_diagnosis_checkpoint(session_id, assistant_message_id DESC);

-- 浏览器可见的 append-only 流投影，cursor 仅在单个 run 内有意义。
CREATE TABLE IF NOT EXISTS chat_run_event (
    run_id       TEXT    NOT NULL,
    seq          INTEGER NOT NULL,
    event_type   TEXT    NOT NULL,
    payload      TEXT    NOT NULL,
    payload_size INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    PRIMARY KEY (run_id, seq),
    CHECK (seq > 0),
    CHECK (payload_size >= 0)
);

CREATE INDEX IF NOT EXISTS idx_chat_run_event_created
    ON chat_run_event(created_at);

-- 对话工具调用旁路结构化投影。原 chat_message.content 与 SSE 仍是聊天展示事实源。
CREATE TABLE IF NOT EXISTS chat_tool_invocation (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id            TEXT    NOT NULL,
    run_id                TEXT,
    assistant_message_id  INTEGER,
    provider              TEXT    NOT NULL,
    provider_call_id      TEXT,
    invocation_index      INTEGER NOT NULL,
    invocation_kind       TEXT    NOT NULL,
    tool_name             TEXT,
    skill_name            TEXT,
    trigger_source        TEXT    NOT NULL,
    input_json            TEXT,
    output_text           TEXT,
    status                TEXT    NOT NULL,
    is_error              INTEGER NOT NULL DEFAULT 0,
    exit_code             INTEGER,
    provider_item_type    TEXT,
    provider_status       TEXT,
    input_truncated       INTEGER NOT NULL DEFAULT 0,
    output_truncated      INTEGER NOT NULL DEFAULT 0,
    output_original_size  INTEGER,
    started_at            INTEGER,
    completed_at          INTEGER,
    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL,
    source                TEXT    NOT NULL,
    source_message_id     INTEGER,
    migration_confidence  TEXT,
    CHECK (provider IN ('CLAUDE', 'CODEX', 'NATIVE')),
    CHECK (invocation_kind IN ('TOOL_USE', 'COMMAND_EXECUTION', 'SKILL')),
    CHECK ((invocation_kind = 'TOOL_USE' AND tool_name IS NOT NULL AND skill_name IS NULL)
        OR (invocation_kind = 'COMMAND_EXECUTION' AND tool_name IS NULL AND skill_name IS NULL)
        OR (invocation_kind = 'SKILL' AND tool_name = 'Skill')),
    CHECK (trigger_source IN ('AGENT', 'USER_SLASH')),
    CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'INCOMPLETE', 'UNKNOWN')),
    CHECK (source IN ('LIVE', 'HISTORY_MIGRATION')),
    CHECK (migration_confidence IS NULL OR migration_confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    CHECK (is_error IN (0, 1)),
    CHECK (input_truncated IN (0, 1)),
    CHECK (output_truncated IN (0, 1))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_tool_invocation_provider_call
    ON chat_tool_invocation(session_id, source_message_id, provider_call_id)
    WHERE source_message_id IS NOT NULL AND provider_call_id IS NOT NULL AND provider_call_id <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_tool_invocation_live_provider_call
    ON chat_tool_invocation(run_id, provider_call_id)
    WHERE run_id IS NOT NULL AND provider_call_id IS NOT NULL AND provider_call_id <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_tool_invocation_message_index
    ON chat_tool_invocation(session_id, source_message_id, invocation_index, trigger_source)
    WHERE source_message_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_tool_invocation_live_index
    ON chat_tool_invocation(run_id, invocation_index, trigger_source)
    WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_session
    ON chat_tool_invocation(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_run
    ON chat_tool_invocation(run_id, invocation_index);
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_message
    ON chat_tool_invocation(assistant_message_id);
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_tool
    ON chat_tool_invocation(tool_name, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_skill
    ON chat_tool_invocation(skill_name, created_at) WHERE skill_name IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_started
    ON chat_tool_invocation(started_at);
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_source_started
    ON chat_tool_invocation(source, started_at);
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_kind_started
    ON chat_tool_invocation(invocation_kind, started_at);
CREATE INDEX IF NOT EXISTS idx_chat_tool_invocation_status_started
    ON chat_tool_invocation(status, started_at);

CREATE TABLE IF NOT EXISTS chat_tool_invocation_migration_state (
    migration_name        TEXT PRIMARY KEY,
    last_message_id       INTEGER NOT NULL,
    scanned_messages      INTEGER NOT NULL,
    inserted_invocations  INTEGER NOT NULL,
    parse_failures        INTEGER NOT NULL,
    replayed_results      INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL
);

-- /recall 命中明细, 1:1 挂在 assistant 消息上 (key=chat_message.id), 供刷新/重开历史时回放召回卡片
CREATE TABLE IF NOT EXISTS chat_message_recall (
    message_id    INTEGER PRIMARY KEY,
    payload_json  TEXT    NOT NULL
);

-- share_token index is created after migration in SqliteInitializer

CREATE TABLE IF NOT EXISTS scheduled_task (
    id              TEXT PRIMARY KEY,
    name            TEXT    NOT NULL,
    cron_expr       TEXT    NOT NULL,
    prompt          TEXT    NOT NULL,
    working_dir     TEXT    NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL,
    last_run_at     TEXT,
    last_session_id TEXT
);

CREATE TABLE IF NOT EXISTS chat_rag_chunk (
    id                 TEXT PRIMARY KEY,
    source_session_id  TEXT    NOT NULL,
    source_msg_range   TEXT,
    title              TEXT    NOT NULL,
    trigger_signals    TEXT,
    context            TEXT,
    process            TEXT,
    conclusion         TEXT,
    ttl_category       TEXT    NOT NULL,
    score              REAL    NOT NULL,
    created_at         INTEGER NOT NULL,
    expires_at         INTEGER,
    archived_at        INTEGER,
    agent_type         TEXT    NOT NULL,
    embedding_model    TEXT    NOT NULL,
    embedding          BLOB    NOT NULL,
    source_type        TEXT    NOT NULL DEFAULT 'CHAT',
    tier               TEXT    NOT NULL DEFAULT 'EXPLORATORY',
    env                TEXT    NOT NULL DEFAULT 'unknown'
);

CREATE INDEX IF NOT EXISTS idx_chat_rag_chunk_session  ON chat_rag_chunk(source_session_id);
CREATE INDEX IF NOT EXISTS idx_chat_rag_chunk_expires  ON chat_rag_chunk(expires_at);
CREATE INDEX IF NOT EXISTS idx_chat_rag_chunk_archived ON chat_rag_chunk(archived_at);
-- 注意: idx_chat_rag_chunk_source(source_type, tier) 不在此处建.
-- source_type/tier/env 三列对老库需经 SqliteInitializer 的 ALTER 迁移才存在,
-- 若在 schema.sql 建索引会跑在迁移之前, 老库上 "no such column: source_type" 启动失败.
-- 该索引统一由 SqliteInitializer 在加列迁移之后幂等创建.

CREATE TABLE IF NOT EXISTS chat_session_rag_state (
    session_id              TEXT PRIMARY KEY,
    last_refined_at         INTEGER NOT NULL,
    last_message_at_seen    INTEGER NOT NULL,
    last_chunk_id           TEXT,
    last_error              TEXT,
    retry_count             INTEGER NOT NULL DEFAULT 0
);

-- below-threshold(评分 < score-threshold) 被丢弃的会话留痕, 供管理台"已丢弃(低分)"展示与阈值校准.
-- 不进 chat_rag_chunk: 无 embedding 不参与召回, 独立表干净隔离, 不污染召回全表扫描.
-- 注意: 注释内不能出现分号, SqliteInitializer 按分号切分语句.
CREATE TABLE IF NOT EXISTS chat_rag_discarded (
    id                 TEXT PRIMARY KEY,
    source_type        TEXT    NOT NULL DEFAULT 'CHAT',   -- CHAT | DIAGNOSE
    source_session_id  TEXT    NOT NULL,
    title              TEXT    NOT NULL,
    conclusion         TEXT,
    ttl_category       TEXT,                              -- LLM 仍会分类, 可空兜底
    score              REAL    NOT NULL,
    threshold          REAL    NOT NULL,                  -- 丢弃时的阈值, 供校准对照
    agent_type         TEXT,
    env                TEXT,
    created_at         INTEGER NOT NULL,                  -- epoch millis
    reason             TEXT    NOT NULL DEFAULT 'score below threshold'
);
CREATE INDEX IF NOT EXISTS idx_chat_rag_discarded_created ON chat_rag_discarded(created_at);

-- Chat RAG recall observability projection: one attempt per user message plus scored hit snapshots.
CREATE TABLE IF NOT EXISTS chat_recall_attempt (
    id                    TEXT PRIMARY KEY,
    session_id             TEXT    NOT NULL,
    user_message_id        INTEGER NOT NULL,
    assistant_message_id   INTEGER,
    query                  TEXT    NOT NULL,
    recall_enabled         INTEGER NOT NULL,
    env                    TEXT,
    status                 TEXT    NOT NULL,
    skip_reason            TEXT,
    hit_count              INTEGER NOT NULL DEFAULT 0,
    top_k                  INTEGER,
    active_count           INTEGER,
    filtered_count         INTEGER,
    below_vector_floor     INTEGER,
    bad_vector_count       INTEGER,
    ranked_count           INTEGER,
    top_vector_score       REAL,
    top_final_score        REAL,
    params_json            TEXT,
    embedding_model        TEXT,
    embedding_dimension    INTEGER,
    latency_ms             INTEGER,
    error_type             TEXT,
    error_message          TEXT,
    created_at             INTEGER NOT NULL,
    updated_at             INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_session
    ON chat_recall_attempt(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_status
    ON chat_recall_attempt(status, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_created
    ON chat_recall_attempt(created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_recall_attempt_user_msg
    ON chat_recall_attempt(user_message_id);
CREATE INDEX IF NOT EXISTS idx_chat_recall_attempt_model
    ON chat_recall_attempt(embedding_model, created_at);

CREATE TABLE IF NOT EXISTS chat_recall_hit (
    attempt_id        TEXT    NOT NULL,
    rank_no           INTEGER NOT NULL,
    chunk_id          TEXT    NOT NULL,
    source_session_id TEXT,
    source_msg_range  TEXT,
    title             TEXT,
    conclusion        TEXT,
    final_score       REAL,
    vector_score      REAL,
    signal_score      REAL,
    time_score        REAL,
    embedding_model   TEXT,
    source_type       TEXT,
    tier              TEXT,
    env               TEXT,
    chunk_score       REAL,
    chunk_created_at  INTEGER,
    created_at        INTEGER NOT NULL,
    PRIMARY KEY (attempt_id, rank_no)
);
CREATE INDEX IF NOT EXISTS idx_chat_recall_hit_chunk
    ON chat_recall_hit(chunk_id);

-- 每用户 git 配置: 提交身份 + push 凭证. 无登录上下文的系统任务走机器默认 git.
-- cred_password_enc 为 AES-256-GCM 密文(含 iv), 绝不存明文. 注释内不能出现分号(按分号切分语句).
CREATE TABLE IF NOT EXISTS user_git_config (
    user_id           TEXT PRIMARY KEY,
    git_name          TEXT,
    git_email         TEXT,
    cred_username     TEXT,
    cred_password_enc TEXT,
    updated_at        INTEGER
);

-- 登录账户: 密码只保存 BCrypt 哈希，不保存明文.
CREATE TABLE IF NOT EXISTS user_account (
    id            TEXT PRIMARY KEY,
    username      TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT    NOT NULL,
    role          TEXT    NOT NULL,
    enabled       INTEGER NOT NULL DEFAULT 1,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

-- 初始管理员只在不存在时创建，重启不会重置已有密码.
INSERT OR IGNORE INTO user_account
    (id, username, password_hash, role, enabled, created_at, updated_at)
VALUES
    ('admin', 'admin', '$2b$12$DKOR1h0GGLppD.lpcl94N.TqktMUO3Bmh19O.moh9qhPzY/..ZdR.',
     'ADMIN', 1, CAST(strftime('%s', 'now') AS INTEGER) * 1000,
     CAST(strftime('%s', 'now') AS INTEGER) * 1000);

-- 本地登录会话: 用户名密码校验通过后创建.
-- session_id 由 ManualSession.create 用 SecureRandom 生成 base64url(32 字节熵).
-- 过期由 expires_at 控制, 后台 tick 用 deleteExpiredBefore 清理.
CREATE TABLE IF NOT EXISTS manual_session (
    session_id  TEXT PRIMARY KEY,
    user_id     TEXT    NOT NULL,
    user_name   TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_manual_session_expires ON manual_session(expires_at);

CREATE TABLE IF NOT EXISTS workflow_definition (
    id          TEXT PRIMARY KEY,
    name        TEXT    NOT NULL,
    description TEXT,
    agent_type  TEXT    NOT NULL,
    working_dir TEXT    NOT NULL,
    steps_json  TEXT    NOT NULL,
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_by  TEXT,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workflow_definition_created
    ON workflow_definition(created_at);

CREATE TABLE IF NOT EXISTS workflow_execution (
    id            TEXT PRIMARY KEY,
    workflow_id   TEXT    NOT NULL,
    status        TEXT    NOT NULL,
    inputs_json   TEXT,
    started_at    INTEGER NOT NULL,
    finished_at   INTEGER,
    error_message TEXT,
    created_by    TEXT
);

CREATE INDEX IF NOT EXISTS idx_workflow_execution_workflow
    ON workflow_execution(workflow_id, started_at);

CREATE INDEX IF NOT EXISTS idx_workflow_execution_status
    ON workflow_execution(status);

CREATE TABLE IF NOT EXISTS workflow_step_execution (
    id            TEXT PRIMARY KEY,
    execution_id  TEXT    NOT NULL,
    step_index    INTEGER NOT NULL,
    step_name     TEXT    NOT NULL,
    status        TEXT    NOT NULL,
    prompt        TEXT    NOT NULL,
    output        TEXT,
    error_message TEXT,
    started_at    INTEGER NOT NULL,
    finished_at   INTEGER
);

-- Harness M1 独立限界上下文，首版仅增表，不复用 Workflow 状态语义.
CREATE TABLE IF NOT EXISTS harness_run (
    id                 TEXT PRIMARY KEY,
    title              TEXT    NOT NULL,
    working_dir        TEXT    NOT NULL,
    agent_type         TEXT    NOT NULL,
    environment        TEXT    NOT NULL,
    definition_version TEXT    NOT NULL,
    created_by         TEXT    NOT NULL,
    idempotency_key    TEXT    NOT NULL,
    status             TEXT    NOT NULL,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    version            INTEGER NOT NULL DEFAULT 0,
    repository_root    TEXT    NOT NULL DEFAULT 'UNKNOWN',
    git_branch         TEXT    NOT NULL DEFAULT 'UNKNOWN',
    git_head           TEXT    NOT NULL DEFAULT '0000000000000000000000000000000000000000',
    git_clean          INTEGER NOT NULL DEFAULT 0,
    git_diff_hash      TEXT    NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    git_captured_at    INTEGER NOT NULL DEFAULT 0,
    UNIQUE(created_by, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_harness_run_status ON harness_run(status, updated_at);

CREATE TABLE IF NOT EXISTS harness_stage_execution (
    run_id               TEXT    NOT NULL,
    stage                TEXT    NOT NULL,
    stage_order          INTEGER NOT NULL,
    status               TEXT    NOT NULL,
    required_inputs_json TEXT    NOT NULL,
    required_outputs_json TEXT   NOT NULL,
    gates_json           TEXT    NOT NULL,
    approval_type        TEXT    NOT NULL,
    PRIMARY KEY(run_id, stage),
    FOREIGN KEY(run_id) REFERENCES harness_run(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS harness_stage_attempt (
    run_id          TEXT    NOT NULL,
    stage           TEXT    NOT NULL,
    attempt_number  INTEGER NOT NULL,
    idempotency_key TEXT    NOT NULL,
    status          TEXT    NOT NULL,
    started_at      INTEGER NOT NULL,
    finished_at     INTEGER,
    failure_reason  TEXT,
    snapshot_hash   TEXT,
    execution_id    TEXT,
    PRIMARY KEY(run_id, stage, attempt_number),
    FOREIGN KEY(run_id, stage) REFERENCES harness_stage_execution(run_id, stage) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS harness_artifact (
    run_id                TEXT    NOT NULL,
    artifact_id           TEXT    NOT NULL,
    artifact_type         TEXT    NOT NULL,
    version               INTEGER NOT NULL,
    stage                 TEXT    NOT NULL,
    attempt_number        INTEGER NOT NULL,
    content_type          TEXT    NOT NULL,
    size_bytes            INTEGER NOT NULL,
    sha256                TEXT    NOT NULL,
    classification        TEXT    NOT NULL,
    created_by            TEXT    NOT NULL,
    created_at            INTEGER NOT NULL,
    source_artifacts_json TEXT    NOT NULL,
    PRIMARY KEY(run_id, artifact_id, version),
    FOREIGN KEY(run_id) REFERENCES harness_run(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_harness_artifact_run_type
    ON harness_artifact(run_id, artifact_type, version);

CREATE TABLE IF NOT EXISTS harness_gate_result (
    result_id             TEXT PRIMARY KEY,
    run_id                TEXT    NOT NULL,
    stage                 TEXT    NOT NULL,
    attempt_number        INTEGER NOT NULL,
    rule                  TEXT    NOT NULL,
    passed                INTEGER NOT NULL,
    artifact_baseline_hash TEXT   NOT NULL,
    evidence_json         TEXT    NOT NULL,
    reason                TEXT,
    evaluated_at          INTEGER NOT NULL,
    FOREIGN KEY(run_id) REFERENCES harness_run(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS harness_approval (
    approval_id           TEXT PRIMARY KEY,
    run_id                TEXT    NOT NULL,
    stage                 TEXT    NOT NULL,
    attempt_number        INTEGER NOT NULL,
    approval_type         TEXT    NOT NULL,
    decision              TEXT    NOT NULL,
    artifact_baseline_hash TEXT   NOT NULL,
    decided_by            TEXT    NOT NULL,
    reason                TEXT    NOT NULL,
    decided_at            INTEGER NOT NULL,
    valid                 INTEGER NOT NULL,
    invalidated_at        INTEGER,
    FOREIGN KEY(run_id) REFERENCES harness_run(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_harness_gate_result_run ON harness_gate_result(run_id);
CREATE INDEX IF NOT EXISTS idx_harness_approval_run ON harness_approval(run_id);

CREATE TABLE IF NOT EXISTS harness_question (
    question_id    TEXT    NOT NULL,
    run_id         TEXT    NOT NULL,
    stage          TEXT    NOT NULL,
    attempt_number INTEGER NOT NULL,
    question       TEXT    NOT NULL,
    blocking       INTEGER NOT NULL,
    asked_by       TEXT    NOT NULL,
    asked_at       INTEGER NOT NULL,
    answer         TEXT,
    answered_by    TEXT,
    answered_at    INTEGER,
    PRIMARY KEY(run_id, question_id),
    FOREIGN KEY(run_id, stage, attempt_number)
        REFERENCES harness_stage_attempt(run_id, stage, attempt_number) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS harness_deployment_execution (
    execution_id                 TEXT PRIMARY KEY,
    idempotency_key              TEXT    NOT NULL,
    run_id                       TEXT    NOT NULL,
    attempt_number               INTEGER NOT NULL,
    approved_input_baseline_hash TEXT    NOT NULL,
    repository_root              TEXT    NOT NULL,
    git_branch                   TEXT    NOT NULL,
    git_head                     TEXT    NOT NULL,
    git_clean                    INTEGER NOT NULL,
    git_diff_hash                TEXT    NOT NULL,
    git_captured_at              INTEGER NOT NULL,
    template_id                  TEXT    NOT NULL,
    template_version             TEXT    NOT NULL,
    template_hash                TEXT    NOT NULL,
    rollback_configured          INTEGER NOT NULL,
    status                       TEXT    NOT NULL,
    failure_reason               TEXT,
    prepared_at                  INTEGER NOT NULL,
    started_at                   INTEGER,
    finished_at                  INTEGER,
    UNIQUE(run_id, idempotency_key),
    FOREIGN KEY(run_id) REFERENCES harness_run(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_harness_deployment_unfinished
    ON harness_deployment_execution(status, prepared_at);
CREATE INDEX IF NOT EXISTS idx_harness_question_run
    ON harness_question(run_id, stage, attempt_number, asked_at);

CREATE TABLE IF NOT EXISTS harness_event (
    run_id      TEXT    NOT NULL,
    sequence    INTEGER NOT NULL,
    event_type  TEXT    NOT NULL,
    stage       TEXT,
    actor       TEXT    NOT NULL,
    detail      TEXT,
    occurred_at INTEGER NOT NULL,
    PRIMARY KEY(run_id, sequence),
    FOREIGN KEY(run_id) REFERENCES harness_run(id) ON DELETE CASCADE
);

-- M2 Capability Snapshot 与 Attempt 一对一绑定；资源变化必须新建 Attempt，禁止原地覆盖。
CREATE TABLE IF NOT EXISTS harness_capability_snapshot (
    run_id                       TEXT    NOT NULL,
    stage                        TEXT    NOT NULL,
    attempt_number               INTEGER NOT NULL,
    runtime                      TEXT    NOT NULL,
    environment                  TEXT    NOT NULL,
    policy_version               TEXT    NOT NULL,
    prompt_pack_id               TEXT    NOT NULL,
    prompt_pack_version          TEXT    NOT NULL,
    prompt_pack_hash             TEXT    NOT NULL,
    prompt_resource_hashes_json  TEXT    NOT NULL,
    selected_skills_json         TEXT    NOT NULL,
    rejected_skills_json         TEXT    NOT NULL,
    capability_decisions_json    TEXT    NOT NULL,
    prompt_parts_json            TEXT    NOT NULL,
    final_prompt                 TEXT    NOT NULL,
    prompt_hash                  TEXT    NOT NULL,
    snapshot_hash                TEXT    NOT NULL,
    created_at                   INTEGER NOT NULL,
    schema_version               TEXT    NOT NULL DEFAULT 'M2',
    selected_mcp_servers_json    TEXT    NOT NULL DEFAULT '[]',
    rejected_mcp_servers_json    TEXT    NOT NULL DEFAULT '[]',
    runtime_enforcement_json     TEXT,
    workspace_runtime_inventory_json TEXT NOT NULL DEFAULT '{}',
    PRIMARY KEY(run_id, stage, attempt_number),
    FOREIGN KEY(run_id, stage, attempt_number)
        REFERENCES harness_stage_attempt(run_id, stage, attempt_number) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_harness_capability_snapshot_hash
    ON harness_capability_snapshot(snapshot_hash);

-- M3 RuntimeExecution 是独立聚合；一个 Attempt 最多绑定一次受控外部执行。
CREATE TABLE IF NOT EXISTS harness_runtime_execution (
    execution_id          TEXT PRIMARY KEY,
    idempotency_key       TEXT    NOT NULL,
    run_id                TEXT    NOT NULL,
    stage                 TEXT    NOT NULL,
    attempt_number        INTEGER NOT NULL,
    snapshot_hash         TEXT    NOT NULL,
    prompt_hash           TEXT    NOT NULL,
    runtime               TEXT    NOT NULL,
    status                TEXT    NOT NULL,
    runtime_version       TEXT,
    runtime_handle        TEXT,
    last_event_sequence   INTEGER NOT NULL DEFAULT 0,
    termination_reason    TEXT,
    exit_code             INTEGER,
    evidence_reference    TEXT,
    cleanup_status        TEXT    NOT NULL,
    prepared_at           INTEGER NOT NULL,
    started_at            INTEGER,
    cancel_requested_at   INTEGER,
    finished_at           INTEGER,
    UNIQUE(run_id, stage, attempt_number),
    UNIQUE(run_id, idempotency_key),
    FOREIGN KEY(run_id, stage, attempt_number)
        REFERENCES harness_stage_attempt(run_id, stage, attempt_number) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_harness_runtime_execution_status
    ON harness_runtime_execution(status, prepared_at);

CREATE TABLE IF NOT EXISTS harness_runtime_event (
    execution_id       TEXT    NOT NULL,
    sequence           INTEGER NOT NULL,
    event_type         TEXT    NOT NULL,
    summary            TEXT,
    evidence_reference TEXT,
    occurred_at        INTEGER NOT NULL,
    PRIMARY KEY(execution_id, sequence),
    FOREIGN KEY(execution_id) REFERENCES harness_runtime_execution(execution_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workflow_step_execution_execution
    ON workflow_step_execution(execution_id, step_index);

-- 运行时可变配置 key-value(管理后台可改、免重启热生效)。当前承载对话默认模型与工作空间配置,
-- yml 仅作未落库时的种子。value 统一存字符串；复合配置使用单个 JSON 文档保证原子更新。
CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   TEXT PRIMARY KEY,
    setting_value TEXT    NOT NULL,
    updated_at    INTEGER NOT NULL
);

-- 公共 Workspace Snapshot 是不可变观察事实；父行保存领域 Hash 与子项数量，恢复时拒绝半截聚合。
CREATE TABLE IF NOT EXISTS workspace_snapshot (
    snapshot_id            TEXT    PRIMARY KEY,
    purpose                TEXT    NOT NULL,
    workspace_root         TEXT    NOT NULL,
    primary_repository_key TEXT    NOT NULL,
    topology_hash          TEXT    NOT NULL,
    clean                  INTEGER NOT NULL,
    state_hash             TEXT    NOT NULL,
    capture_started_at     INTEGER NOT NULL,
    captured_at            INTEGER NOT NULL,
    repository_count       INTEGER NOT NULL,
    anomaly_count          INTEGER NOT NULL,
    CHECK (length(snapshot_id) BETWEEN 1 AND 128),
    CHECK (length(purpose) BETWEEN 1 AND 64
        AND purpose GLOB '[A-Z]*' AND purpose NOT GLOB '*[^A-Z0-9_]*'),
    CHECK (length(workspace_root) BETWEEN 1 AND 4096),
    CHECK (length(primary_repository_key) BETWEEN 1 AND 512),
    CHECK (length(topology_hash) = 64
        AND topology_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (clean IN (0, 1)),
    CHECK (length(state_hash) = 64
        AND state_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (captured_at >= capture_started_at),
    CHECK (repository_count >= 1),
    CHECK (anomaly_count >= 0)
);

CREATE TABLE IF NOT EXISTS workspace_snapshot_repository (
    snapshot_id         TEXT    NOT NULL,
    repository_key      TEXT    NOT NULL,
    repository_order    INTEGER NOT NULL,
    repository_root     TEXT    NOT NULL,
    branch              TEXT    NOT NULL,
    git_head            TEXT    NOT NULL,
    clean               INTEGER NOT NULL,
    diff_hash           TEXT    NOT NULL,
    captured_at         INTEGER NOT NULL,
    primary_repository  INTEGER NOT NULL,
    changed_file_count  INTEGER NOT NULL,
    PRIMARY KEY(snapshot_id, repository_key),
    UNIQUE(snapshot_id, repository_order),
    FOREIGN KEY(snapshot_id) REFERENCES workspace_snapshot(snapshot_id) ON DELETE CASCADE,
    CHECK (length(repository_key) BETWEEN 1 AND 512),
    CHECK (repository_order >= 0),
    CHECK (length(repository_root) BETWEEN 1 AND 4096),
    CHECK (length(branch) BETWEEN 1 AND 512),
    CHECK (length(git_head) IN (40, 64)
        AND git_head NOT GLOB '*[^0-9a-f]*'),
    CHECK (clean IN (0, 1)),
    CHECK (length(diff_hash) = 64
        AND diff_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (primary_repository IN (0, 1)),
    CHECK (changed_file_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workspace_snapshot_one_primary
    ON workspace_snapshot_repository(snapshot_id) WHERE primary_repository = 1;

CREATE TABLE IF NOT EXISTS workspace_snapshot_changed_file (
    snapshot_id       TEXT    NOT NULL,
    repository_key    TEXT    NOT NULL,
    file_path         TEXT    NOT NULL,
    file_order        INTEGER NOT NULL,
    status            TEXT    NOT NULL,
    state_fingerprint TEXT    NOT NULL,
    sensitive         INTEGER NOT NULL,
    PRIMARY KEY(snapshot_id, repository_key, file_path),
    UNIQUE(snapshot_id, repository_key, file_order),
    FOREIGN KEY(snapshot_id, repository_key)
        REFERENCES workspace_snapshot_repository(snapshot_id, repository_key)
        ON DELETE CASCADE,
    CHECK (length(file_path) BETWEEN 1 AND 4096),
    CHECK (file_order >= 0),
    CHECK (length(status) BETWEEN 1 AND 64),
    CHECK (length(state_fingerprint) = 64
        AND state_fingerprint NOT GLOB '*[^0-9a-f]*'),
    CHECK (sensitive IN (0, 1))
);

CREATE TABLE IF NOT EXISTS workspace_snapshot_anomaly (
    snapshot_id    TEXT    NOT NULL,
    anomaly_order  INTEGER NOT NULL,
    kind           TEXT    NOT NULL,
    repository_key TEXT,
    detail         TEXT    NOT NULL,
    PRIMARY KEY(snapshot_id, anomaly_order),
    FOREIGN KEY(snapshot_id) REFERENCES workspace_snapshot(snapshot_id) ON DELETE CASCADE,
    FOREIGN KEY(snapshot_id, repository_key)
        REFERENCES workspace_snapshot_repository(snapshot_id, repository_key)
        ON DELETE CASCADE,
    CHECK (anomaly_order >= 0),
    CHECK (kind IN ('CAPTURE_FAILED', 'OUTPUT_TRUNCATED', 'PATH_OUT_OF_BOUNDS',
        'SECONDARY_VERIFY_MISMATCH', 'CHANGED_FILES_LIMIT_EXCEEDED', 'OTHER')),
    CHECK (repository_key IS NULL OR length(repository_key) BETWEEN 1 AND 512),
    CHECK (length(detail) BETWEEN 1 AND 2048)
);

CREATE INDEX IF NOT EXISTS idx_workspace_snapshot_purpose_captured
    ON workspace_snapshot(purpose, captured_at);
CREATE INDEX IF NOT EXISTS idx_workspace_snapshot_anomaly_kind
    ON workspace_snapshot_anomaly(kind, snapshot_id);

-- Local Development Workbench 写模型。Workbench 是状态唯一来源；归档只读保留，不提供物理删除端口。
CREATE TABLE IF NOT EXISTS workbench (
    id                                TEXT    PRIMARY KEY,
    owner_id                          TEXT    NOT NULL,
    owner_name                        TEXT    NOT NULL,
    title                             TEXT    NOT NULL,
    original_goal                     TEXT    NOT NULL,
    agent_type                        TEXT    NOT NULL,
    environment                       TEXT,
    workspace_root                    TEXT    NOT NULL,
    primary_repository_key            TEXT    NOT NULL,
    repository_scope_hash             TEXT    NOT NULL,
    creation_snapshot_id              TEXT    NOT NULL,
    creation_snapshot_topology_hash   TEXT    NOT NULL,
    creation_snapshot_state_hash      TEXT    NOT NULL,
    creation_snapshot_repository_count INTEGER NOT NULL,
    active_write_run_id               TEXT,
    status                            TEXT    NOT NULL,
    created_at                        INTEGER NOT NULL,
    updated_at                        INTEGER NOT NULL,
    version                           INTEGER NOT NULL,
    FOREIGN KEY(creation_snapshot_id)
        REFERENCES workspace_snapshot(snapshot_id) ON DELETE RESTRICT,
    CHECK (length(id) BETWEEN 1 AND 128),
    CHECK (length(owner_id) BETWEEN 1 AND 128),
    CHECK (length(owner_name) BETWEEN 1 AND 256),
    CHECK (length(title) BETWEEN 1 AND 512),
    CHECK (length(original_goal) BETWEEN 1 AND 16000),
    CHECK (agent_type IN ('CODEX', 'CLAUDE', 'NATIVE')),
    CHECK (environment IS NULL OR length(environment) BETWEEN 1 AND 256),
    CHECK (length(workspace_root) BETWEEN 1 AND 4096),
    CHECK (length(primary_repository_key) BETWEEN 1 AND 512),
    CHECK (length(repository_scope_hash) = 64
        AND repository_scope_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (length(creation_snapshot_topology_hash) = 64
        AND creation_snapshot_topology_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (length(creation_snapshot_state_hash) = 64
        AND creation_snapshot_state_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (creation_snapshot_repository_count >= 1),
    CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CHECK (updated_at >= created_at),
    CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS workbench_repository_scope (
    workbench_id        TEXT    NOT NULL,
    repository_key     TEXT    NOT NULL,
    relative_path      TEXT    NOT NULL,
    repository_root    TEXT    NOT NULL,
    root_fingerprint   TEXT    NOT NULL,
    primary_repository INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, repository_key),
    UNIQUE(workbench_id, repository_root),
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE CASCADE,
    CHECK (length(repository_key) BETWEEN 1 AND 512),
    CHECK (relative_path = repository_key),
    CHECK (length(repository_root) BETWEEN 1 AND 4096),
    CHECK (length(root_fingerprint) = 64
        AND root_fingerprint NOT GLOB '*[^0-9a-f]*'),
    CHECK (primary_repository IN (0, 1))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workbench_scope_one_primary
    ON workbench_repository_scope(workbench_id) WHERE primary_repository = 1;

CREATE TABLE IF NOT EXISTS workbench_phase (
    workbench_id               TEXT    NOT NULL,
    phase                      TEXT    NOT NULL,
    phase_order                INTEGER NOT NULL,
    status                     TEXT    NOT NULL,
    conversation_generation    INTEGER NOT NULL,
    active_run_id              TEXT,
    active_run_mode            TEXT,
    active_run_prepared_at     INTEGER,
    review_confirmation_id     TEXT,
    review_opinion_version     INTEGER,
    review_opinion_hash        TEXT,
    last_activity_at           INTEGER,
    completed_at               INTEGER,
    PRIMARY KEY(workbench_id, phase),
    UNIQUE(workbench_id, phase_order),
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE CASCADE,
    CHECK ((phase = 'REQUIREMENT_ANALYSIS' AND phase_order = 0)
        OR (phase = 'SOLUTION_DESIGN' AND phase_order = 1)
        OR (phase = 'IMPLEMENT_TEST' AND phase_order = 2)
        OR (phase = 'REVIEW_REFACTOR' AND phase_order = 3)),
    CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'HUMAN_COMPLETED')),
    CHECK (conversation_generation >= 0),
    CHECK (active_run_mode IS NULL
        OR active_run_mode IN ('DISCUSS_READ_ONLY', 'MODIFY_WORKSPACE')),
    CHECK ((active_run_id IS NULL AND active_run_mode IS NULL
            AND active_run_prepared_at IS NULL)
        OR (active_run_id IS NOT NULL AND active_run_mode IS NOT NULL
            AND active_run_prepared_at IS NOT NULL)),
    CHECK ((review_confirmation_id IS NULL AND review_opinion_version IS NULL
            AND review_opinion_hash IS NULL)
        OR (review_confirmation_id IS NOT NULL AND review_opinion_version >= 1
            AND length(review_opinion_hash) = 64
            AND review_opinion_hash NOT GLOB '*[^0-9a-f]*'
            AND phase = 'REVIEW_REFACTOR'
            AND active_run_mode = 'MODIFY_WORKSPACE')),
    CHECK (status = 'HUMAN_COMPLETED' OR completed_at IS NULL),
    CHECK (status <> 'HUMAN_COMPLETED' OR completed_at IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workbench_one_modify_run
    ON workbench_phase(workbench_id) WHERE active_run_mode = 'MODIFY_WORKSPACE';

CREATE TABLE IF NOT EXISTS workbench_phase_conversation (
    workbench_id   TEXT    NOT NULL,
    phase          TEXT    NOT NULL,
    generation     INTEGER NOT NULL,
    session_id     TEXT    NOT NULL,
    created_by_id  TEXT    NOT NULL,
    created_by_name TEXT   NOT NULL,
    created_at     INTEGER NOT NULL,
    retired_at     INTEGER,
    PRIMARY KEY(workbench_id, phase, generation),
    UNIQUE(session_id),
    FOREIGN KEY(workbench_id, phase)
        REFERENCES workbench_phase(workbench_id, phase) ON DELETE CASCADE,
    CHECK (generation >= 0),
    CHECK (length(session_id) BETWEEN 1 AND 128),
    CHECK (length(created_by_id) BETWEEN 1 AND 128),
    CHECK (length(created_by_name) BETWEEN 1 AND 256),
    CHECK (retired_at IS NULL OR retired_at >= created_at)
);

CREATE TABLE IF NOT EXISTS workbench_phase_handoff (
    workbench_id         TEXT    NOT NULL,
    phase                TEXT    NOT NULL,
    summary              TEXT    NOT NULL,
    decisions_json       TEXT    NOT NULL,
    open_questions_json  TEXT    NOT NULL,
    pinned_files_json    TEXT    NOT NULL,
    referenced_runs_json TEXT    NOT NULL,
    content_hash         TEXT    NOT NULL,
    updated_by_id        TEXT    NOT NULL,
    updated_by_name      TEXT    NOT NULL,
    updated_at           INTEGER NOT NULL,
    version              INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, phase),
    FOREIGN KEY(workbench_id, phase)
        REFERENCES workbench_phase(workbench_id, phase) ON DELETE RESTRICT,
    CHECK (length(summary) <= 8000),
    CHECK (length(content_hash) = 64
        AND content_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS workbench_phase_handoff_revision (
    workbench_id         TEXT    NOT NULL,
    phase                TEXT    NOT NULL,
    summary              TEXT    NOT NULL,
    decisions_json       TEXT    NOT NULL,
    open_questions_json  TEXT    NOT NULL,
    pinned_files_json    TEXT    NOT NULL,
    referenced_runs_json TEXT    NOT NULL,
    content_hash         TEXT    NOT NULL,
    updated_by_id        TEXT    NOT NULL,
    updated_by_name      TEXT    NOT NULL,
    updated_at           INTEGER NOT NULL,
    version              INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, phase, version),
    FOREIGN KEY(workbench_id, phase)
        REFERENCES workbench_phase_handoff(workbench_id, phase) ON DELETE RESTRICT,
    CHECK (length(summary) <= 8000),
    CHECK (length(content_hash) = 64
        AND content_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_workbench_handoff_revision_exact
    ON workbench_phase_handoff_revision(
        workbench_id, phase, version, content_hash
    );

CREATE TABLE IF NOT EXISTS workbench_handoff_reception (
    workbench_id     TEXT    NOT NULL,
    target_phase     TEXT    NOT NULL,
    source_phase     TEXT    NOT NULL,
    source_version   INTEGER NOT NULL,
    source_hash      TEXT    NOT NULL,
    accepted_by_id   TEXT    NOT NULL,
    accepted_by_name TEXT    NOT NULL,
    accepted_at      INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, target_phase, source_phase),
    FOREIGN KEY(workbench_id, target_phase)
        REFERENCES workbench_phase(workbench_id, phase) ON DELETE RESTRICT,
    FOREIGN KEY(workbench_id, source_phase)
        REFERENCES workbench_phase_handoff(workbench_id, phase) ON DELETE RESTRICT,
    CHECK (source_version >= 0),
    CHECK (length(source_hash) = 64
        AND source_hash NOT GLOB '*[^0-9a-f]*')
);

CREATE TABLE IF NOT EXISTS workbench_phase_capability_config (
    workbench_id        TEXT    NOT NULL,
    phase               TEXT    NOT NULL,
    base_profile_id     TEXT    NOT NULL,
    base_profile_version TEXT   NOT NULL,
    override_json       TEXT    NOT NULL,
    updated_by_id       TEXT    NOT NULL,
    updated_by_name     TEXT    NOT NULL,
    updated_at          INTEGER NOT NULL,
    version             INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, phase),
    FOREIGN KEY(workbench_id, phase)
        REFERENCES workbench_phase(workbench_id, phase) ON DELETE RESTRICT,
    CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS workbench_review_opinion (
    workbench_id    TEXT    NOT NULL,
    opinion_version INTEGER NOT NULL,
    opinion_content TEXT,
    content_hash    TEXT    NOT NULL,
    reviewed_by_id  TEXT    NOT NULL,
    reviewed_by_name TEXT  NOT NULL,
    reviewed_at     INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, opinion_version),
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE RESTRICT,
    CHECK (opinion_version >= 1),
    CHECK (opinion_content IS NULL
        OR length(trim(opinion_content)) BETWEEN 1 AND 16000),
    CHECK (length(content_hash) = 64
        AND content_hash NOT GLOB '*[^0-9a-f]*')
);

CREATE TABLE IF NOT EXISTS workbench_review_modify_confirmation (
    confirmation_id   TEXT    PRIMARY KEY,
    workbench_id      TEXT    NOT NULL,
    opinion_version   INTEGER NOT NULL,
    opinion_hash      TEXT    NOT NULL,
    confirmed_by_id   TEXT    NOT NULL,
    confirmed_by_name TEXT    NOT NULL,
    confirmed_at      INTEGER NOT NULL,
    FOREIGN KEY(workbench_id, opinion_version)
        REFERENCES workbench_review_opinion(workbench_id, opinion_version)
        ON DELETE RESTRICT,
    CHECK (length(confirmation_id) BETWEEN 1 AND 128),
    CHECK (opinion_version >= 1),
    CHECK (length(opinion_hash) = 64
        AND opinion_hash NOT GLOB '*[^0-9a-f]*')
);

CREATE TABLE IF NOT EXISTS workbench_run_snapshot (
    run_id                          TEXT    PRIMARY KEY,
    workbench_id                    TEXT    NOT NULL,
    phase                           TEXT    NOT NULL,
    submission_idempotency_key      TEXT    NOT NULL,
    submission_request_hash         TEXT    NOT NULL,
    run_mode                        TEXT    NOT NULL,
    repository_scope_hash           TEXT    NOT NULL,
    workspace_snapshot_id           TEXT    NOT NULL,
    workspace_snapshot_topology_hash TEXT   NOT NULL,
    workspace_snapshot_state_hash   TEXT    NOT NULL,
    workspace_snapshot_repository_count INTEGER NOT NULL,
    profile_id                      TEXT    NOT NULL,
    profile_version                 TEXT    NOT NULL,
    override_version                INTEGER,
    capability_bindings_json        TEXT    NOT NULL,
    capability_snapshot_hash        TEXT    NOT NULL,
    handoff_source_phase            TEXT,
    handoff_source_version          INTEGER,
    handoff_source_hash             TEXT,
    prompt_parts_json               TEXT    NOT NULL,
    prompt_hash                     TEXT    NOT NULL,
    attachments_json                TEXT    NOT NULL DEFAULT '[]',
    runtime_enforcement_json        TEXT    NOT NULL,
    review_confirmation_id          TEXT,
    review_opinion_version          INTEGER,
    review_opinion_hash             TEXT,
    created_at                      INTEGER NOT NULL,
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE RESTRICT,
    FOREIGN KEY(workspace_snapshot_id)
        REFERENCES workspace_snapshot(snapshot_id) ON DELETE RESTRICT,
    FOREIGN KEY(review_confirmation_id)
        REFERENCES workbench_review_modify_confirmation(confirmation_id)
        ON DELETE RESTRICT,
    CHECK (phase IN ('REQUIREMENT_ANALYSIS', 'SOLUTION_DESIGN',
        'IMPLEMENT_TEST', 'REVIEW_REFACTOR')),
    CHECK (length(submission_idempotency_key) BETWEEN 1 AND 128),
    CHECK (length(submission_request_hash) = 64
        AND submission_request_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (run_mode IN ('DISCUSS_READ_ONLY', 'MODIFY_WORKSPACE')),
    CHECK (length(repository_scope_hash) = 64
        AND repository_scope_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (length(workspace_snapshot_topology_hash) = 64
        AND workspace_snapshot_topology_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (length(workspace_snapshot_state_hash) = 64
        AND workspace_snapshot_state_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (workspace_snapshot_repository_count >= 1),
    CHECK (override_version IS NULL OR override_version >= 0),
    CHECK (length(capability_snapshot_hash) = 64
        AND capability_snapshot_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK ((handoff_source_phase IS NULL AND handoff_source_version IS NULL
            AND handoff_source_hash IS NULL)
        OR (handoff_source_phase IS NOT NULL AND handoff_source_version >= 0
            AND length(handoff_source_hash) = 64
            AND handoff_source_hash NOT GLOB '*[^0-9a-f]*')),
    CHECK (length(prompt_hash) = 64
        AND prompt_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK ((review_confirmation_id IS NULL AND review_opinion_version IS NULL
            AND review_opinion_hash IS NULL)
        OR (review_confirmation_id IS NOT NULL AND review_opinion_version >= 1
            AND length(review_opinion_hash) = 64
            AND review_opinion_hash NOT GLOB '*[^0-9a-f]*'
            AND phase = 'REVIEW_REFACTOR' AND run_mode = 'MODIFY_WORKSPACE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workbench_run_snapshot_prompt_binding
    ON workbench_run_snapshot(run_id, prompt_hash, created_at);

-- Runtime 真正接收的私有 Prompt；公开 API 只暴露 Snapshot Hash，不读取本表正文。
CREATE TABLE IF NOT EXISTS workbench_run_prompt_payload (
    run_id            TEXT    PRIMARY KEY,
    final_prompt      TEXT    NOT NULL,
    prompt_hash       TEXT    NOT NULL,
    history_delivery  TEXT    NOT NULL,
    created_at        INTEGER NOT NULL,
    FOREIGN KEY(run_id, prompt_hash, created_at)
        REFERENCES workbench_run_snapshot(run_id, prompt_hash, created_at)
        ON DELETE RESTRICT,
    CHECK (length(trim(final_prompt)) >= 1),
    CHECK (length(prompt_hash) = 64
        AND prompt_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (history_delivery IN ('PROMPT_PREFIX', 'PROVIDER_RESUME', 'TYPED'))
);

CREATE TABLE IF NOT EXISTS workbench_high_impact_operation (
    operation_id             TEXT    PRIMARY KEY,
    workbench_id             TEXT    NOT NULL,
    source_run_id            TEXT    NOT NULL,
    source_run_safe_summary  TEXT    NOT NULL,
    phase                    TEXT    NOT NULL,
    operation_type           TEXT    NOT NULL,
    target_json              TEXT    NOT NULL,
    requested_payload_hash   TEXT    NOT NULL,
    safe_summary             TEXT    NOT NULL,
    status                   TEXT    NOT NULL,
    proposed_by_id           TEXT    NOT NULL,
    proposed_by_name         TEXT    NOT NULL,
    proposed_at              INTEGER NOT NULL,
    decided_by_id            TEXT,
    decided_by_name          TEXT,
    decision_reason          TEXT,
    decided_at               INTEGER,
    authorization_expires_at INTEGER,
    preflight_hash           TEXT,
    execution_reference      TEXT,
    failure_code             TEXT,
    updated_at               INTEGER NOT NULL,
    version                  INTEGER NOT NULL,
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE RESTRICT,
    CHECK (phase IN ('REQUIREMENT_ANALYSIS', 'SOLUTION_DESIGN',
        'IMPLEMENT_TEST', 'REVIEW_REFACTOR')),
    CHECK (operation_type IN ('GIT_COMMIT', 'GIT_PUSH',
        'LOCAL_DEPLOY', 'PRODUCTION_WRITE')),
    CHECK (length(requested_payload_hash) = 64
        AND requested_payload_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (status IN ('PROPOSED', 'AUTHORIZED', 'EXECUTING', 'SUCCEEDED',
        'FAILED', 'RECONCILIATION_REQUIRED', 'REJECTED', 'EXPIRED')),
    CHECK ((decided_by_id IS NULL AND decided_by_name IS NULL)
        OR (decided_by_id IS NOT NULL AND decided_by_name IS NOT NULL)),
    CHECK (authorization_expires_at IS NULL OR decided_at IS NOT NULL),
    CHECK (preflight_hash IS NULL OR (length(preflight_hash) = 64
        AND preflight_hash NOT GLOB '*[^0-9a-f]*')),
    CHECK (updated_at >= proposed_at),
    CHECK (version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_workbench_owner_updated
    ON workbench(owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_workbench_status_updated
    ON workbench(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_workbench_run_snapshot_workbench
    ON workbench_run_snapshot(workbench_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_workbench_operation_workbench
    ON workbench_high_impact_operation(workbench_id, proposed_at DESC);
CREATE INDEX IF NOT EXISTS idx_workbench_operation_status
    ON workbench_high_impact_operation(status, updated_at);

CREATE TABLE IF NOT EXISTS workbench_creation_request (
    owner_id        TEXT    NOT NULL,
    owner_name      TEXT    NOT NULL,
    idempotency_key TEXT    NOT NULL,
    request_hash    TEXT    NOT NULL,
    workbench_id    TEXT    NOT NULL,
    created_at      INTEGER NOT NULL,
    PRIMARY KEY(owner_id, idempotency_key),
    UNIQUE(workbench_id),
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE RESTRICT,
    CHECK (length(owner_id) BETWEEN 1 AND 128),
    CHECK (length(owner_name) BETWEEN 1 AND 256),
    CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CHECK (length(request_hash) = 64
        AND request_hash NOT GLOB '*[^0-9a-f]*')
);

CREATE TABLE IF NOT EXISTS workbench_phase_conversation_restart_receipt (
    owner_id               TEXT    NOT NULL,
    owner_name             TEXT    NOT NULL,
    idempotency_key        TEXT    NOT NULL,
    workbench_id           TEXT    NOT NULL,
    phase                  TEXT    NOT NULL,
    previous_session_id    TEXT    NOT NULL,
    session_id             TEXT    NOT NULL,
    conversation_generation INTEGER NOT NULL,
    workbench_version      INTEGER NOT NULL,
    created_at             INTEGER NOT NULL,
    PRIMARY KEY(owner_id, idempotency_key),
    UNIQUE(session_id),
    FOREIGN KEY(workbench_id, phase)
        REFERENCES workbench_phase(workbench_id, phase) ON DELETE RESTRICT,
    FOREIGN KEY(previous_session_id) REFERENCES chat_session(id) ON DELETE RESTRICT,
    FOREIGN KEY(session_id) REFERENCES chat_session(id) ON DELETE RESTRICT,
    CHECK (length(owner_id) BETWEEN 1 AND 128),
    CHECK (length(owner_name) BETWEEN 1 AND 256),
    CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CHECK (phase IN ('REQUIREMENT_ANALYSIS', 'SOLUTION_DESIGN',
        'IMPLEMENT_TEST', 'REVIEW_REFACTOR')),
    CHECK (length(previous_session_id) BETWEEN 1 AND 128),
    CHECK (length(session_id) BETWEEN 1 AND 128),
    CHECK (previous_session_id <> session_id),
    CHECK (conversation_generation >= 1),
    CHECK (workbench_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_workbench_conversation_restart_created
    ON workbench_phase_conversation_restart_receipt(workbench_id, phase, created_at DESC);
