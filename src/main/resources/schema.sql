CREATE TABLE IF NOT EXISTS chat_session (
    id          TEXT PRIMARY KEY,
    agent_type  TEXT    NOT NULL,
    working_dir TEXT    NOT NULL,
    created_at  TEXT    NOT NULL,
    resume_id   TEXT
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

CREATE TABLE IF NOT EXISTS scheduled_task (
    id              TEXT PRIMARY KEY,
    name            TEXT    NOT NULL,
    cron_expr       TEXT    NOT NULL,
    prompt          TEXT    NOT NULL,
    agent_type      TEXT    NOT NULL,
    working_dir     TEXT    NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL,
    last_run_at     TEXT,
    last_session_id TEXT
);
