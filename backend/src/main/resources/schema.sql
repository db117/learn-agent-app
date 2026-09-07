CREATE TABLE IF NOT EXISTS "session"
(
    id
    TEXT
    PRIMARY
    KEY,
    user_id
    TEXT
    NOT
    NULL,
    title
    TEXT
    NOT
    NULL,
    created_at
    TEXT
    NOT
    NULL,
    updated_at
    TEXT
    NOT
    NULL
);

CREATE TABLE IF NOT EXISTS message
(
    id
    TEXT
    PRIMARY
    KEY,
    session_id
    TEXT
    NOT
    NULL
    REFERENCES
    "session"
(
    id
),
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent_run
(
    id
    TEXT
    PRIMARY
    KEY,
    session_id
    TEXT
    NOT
    NULL
    REFERENCES
    "session"
(
    id
),
    status TEXT NOT NULL,
    error_message TEXT,
    started_at TEXT NOT NULL,
    completed_at TEXT
    );

CREATE TABLE IF NOT EXISTS "event"
(
    sequence
    INTEGER
    PRIMARY
    KEY
    AUTOINCREMENT,
    id
    TEXT
    NOT
    NULL
    UNIQUE,
    session_id
    TEXT
    NOT
    NULL
    REFERENCES
    "session"
(
    id
),
    run_id TEXT NOT NULL,
    author TEXT NOT NULL,
    event_type TEXT NOT NULL,
    content TEXT NOT NULL,
    tool_call_json TEXT,
    tool_result_json TEXT,
    timestamp TEXT NOT NULL,
    raw_json TEXT NOT NULL
    );

CREATE TABLE IF NOT EXISTS setting
(
    key
    TEXT
    PRIMARY
    KEY,
    value
    TEXT
    NOT
    NULL,
    updated_at
    TEXT
    NOT
    NULL
);

CREATE INDEX IF NOT EXISTS message_session_idx ON message(session_id, created_at);
CREATE INDEX IF NOT EXISTS event_session_idx ON "event"(session_id, sequence);
CREATE INDEX IF NOT EXISTS run_session_idx ON agent_run(session_id, started_at);
