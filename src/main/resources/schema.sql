CREATE TABLE IF NOT EXISTS chat_session (
    id          TEXT PRIMARY KEY,
    agent_type  TEXT    NOT NULL,
    working_dir TEXT    NOT NULL,
    created_at  TEXT    NOT NULL
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
