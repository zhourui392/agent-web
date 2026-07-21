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

-- 本地登录会话: 用户在 /login.html 输入工号 + 用户名后创建.
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

CREATE INDEX IF NOT EXISTS idx_workflow_step_execution_execution
    ON workflow_step_execution(execution_id, step_index);

-- 运行时可变配置 key-value(管理后台可改、免重启热生效)。当前承载对话默认模型开关,
-- yml 仅作首启种子,落库后以本表为准。value 统一存字符串,语义由读取方解析。
CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   TEXT PRIMARY KEY,
    setting_value TEXT    NOT NULL,
    updated_at    INTEGER NOT NULL
);

-- 需求线(M0): Requirement 聚合 + 状态迁移审计。requirement_event 是迁移审计的唯一事实源,
-- 由聚合 pullEvents 经 Repository 落库,只追加不回读进聚合。
CREATE TABLE IF NOT EXISTS requirement (
    id                    TEXT PRIMARY KEY,
    title                 TEXT    NOT NULL,
    description           TEXT,
    status                TEXT    NOT NULL,
    status_before_suspend TEXT,
    source_type           TEXT    NOT NULL,
    source_ref            TEXT,
    owner                 TEXT    NOT NULL,
    participants_json     TEXT,
    workspace_id          TEXT,
    plan_json             TEXT,
    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_requirement_status ON requirement(status);

CREATE INDEX IF NOT EXISTS idx_requirement_owner ON requirement(owner);

CREATE TABLE IF NOT EXISTS requirement_event (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    requirement_id TEXT    NOT NULL,
    event_type     TEXT    NOT NULL,
    actor          TEXT,
    from_status    TEXT,
    to_status      TEXT,
    payload_json   TEXT,
    created_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_requirement_event_rid ON requirement_event(requirement_id);

-- 需求工作区(M1): git worktree 隔离单元。port_lease 是纯 infra 资源表(端口全局池),
-- 以 workspace_id 关联、随工作区释放整体删除,聚合不感知(detailed-design §2.5)。
CREATE TABLE IF NOT EXISTS requirement_workspace (
    id             TEXT PRIMARY KEY,
    requirement_id TEXT    NOT NULL,
    repo_url       TEXT    NOT NULL,
    mirror_path    TEXT    NOT NULL,
    worktree_path  TEXT    NOT NULL,
    branch         TEXT    NOT NULL,
    status         TEXT    NOT NULL,
    ttl_hours      INTEGER NOT NULL,
    last_active_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_req_workspace_rid ON requirement_workspace(requirement_id);

CREATE INDEX IF NOT EXISTS idx_req_workspace_idle ON requirement_workspace(status, last_active_at);

CREATE TABLE IF NOT EXISTS port_lease (
    port         INTEGER PRIMARY KEY,
    workspace_id TEXT    NOT NULL,
    leased_at    INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_port_lease_wid ON port_lease(workspace_id);

-- 交付(M2): MR 引用镜像 + webhook 幂等去重。UNIQUE(requirement_id, mr_iid) 防 webhook 重放插重复行,
-- processed_webhook 随 cleanup cron 顺带删 received_at 超 30 天的行。
CREATE TABLE IF NOT EXISTS merge_request_ref (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    requirement_id  TEXT    NOT NULL,
    mr_iid          INTEGER NOT NULL,
    mr_url          TEXT    NOT NULL,
    draft           INTEGER NOT NULL,
    pipeline_status TEXT,
    updated_at      INTEGER NOT NULL,
    UNIQUE (requirement_id, mr_iid)
);

CREATE TABLE IF NOT EXISTS processed_webhook (
    event_uuid  TEXT PRIMARY KEY,
    received_at INTEGER NOT NULL
);

-- 验证闭环(M2.5): L1 工件落库。内容 <=64KB 存 content,超限存平台侧文件路径,
-- M4.5 verification_round 建立后本表成为其证据源。
CREATE TABLE IF NOT EXISTS requirement_artifact (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    requirement_id TEXT    NOT NULL,
    kind           TEXT    NOT NULL,
    content        TEXT,
    file_path      TEXT,
    created_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_req_artifact_rid ON requirement_artifact(requirement_id);

-- 外部系统建需求幂等(M2): (api_key_name, idem_key) 二元组,对齐 diagnose API 模式
CREATE TABLE IF NOT EXISTS requirement_intake_dedup (
    api_key_name   TEXT    NOT NULL,
    idem_key       TEXT    NOT NULL,
    requirement_id TEXT    NOT NULL,
    created_at     INTEGER NOT NULL,
    PRIMARY KEY (api_key_name, idem_key)
);

-- 知识建议收件箱(M4): run/交付产出的知识候选,人工审批门是入库唯一出口,
-- 批准后经 issue-log 写盘通道落盘并回填 issue_id
CREATE TABLE IF NOT EXISTS knowledge_suggestion (
    id                   TEXT PRIMARY KEY,
    requirement_id       TEXT    NOT NULL,
    scope                TEXT    NOT NULL,
    source_ref           TEXT,
    title                TEXT    NOT NULL,
    trigger_signals_json TEXT,
    phenomenon           TEXT,
    root_cause           TEXT,
    solution             TEXT,
    notes                TEXT,
    status               TEXT    NOT NULL,
    reject_reason        TEXT,
    reviewed_by          TEXT,
    reviewed_at          INTEGER,
    issue_id             TEXT,
    issue_path           TEXT,
    created_at           INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_knowledge_suggestion_status ON knowledge_suggestion(status);

CREATE INDEX IF NOT EXISTS idx_knowledge_suggestion_req ON knowledge_suggestion(requirement_id);

-- 验证轮次(M4.5 轮次化第一步): 每次验证 run 终结落一行, requirement_artifact 是其证据源
CREATE TABLE IF NOT EXISTS verification_round (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    requirement_id TEXT    NOT NULL,
    round          INTEGER NOT NULL,
    deploy_ref     TEXT,
    verdict        TEXT    NOT NULL,
    failed_count   INTEGER NOT NULL DEFAULT 0,
    evidence_ref   TEXT,
    created_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_verification_round_req ON verification_round(requirement_id);
