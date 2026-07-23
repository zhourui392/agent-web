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
    client_ip        TEXT
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

CREATE TABLE IF NOT EXISTS user_suggestion (
    id          TEXT PRIMARY KEY,
    user_id     TEXT,
    user_name   TEXT,
    title       TEXT,
    content     TEXT    NOT NULL,
    contact     TEXT,
    status      TEXT    NOT NULL,
    admin_reply TEXT,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    replied_at  INTEGER
);

CREATE INDEX IF NOT EXISTS idx_user_suggestion_user ON user_suggestion(user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_user_suggestion_status ON user_suggestion(status, updated_at);

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
