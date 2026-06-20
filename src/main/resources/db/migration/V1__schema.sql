-- M1 schema. The comment tree is a self-referencing table; branch context is read with
-- recursive CTEs (see the sqlite-spring-jdbc skill). Timestamps are ISO-8601 TEXT.

CREATE TABLE thread (
    id         TEXT PRIMARY KEY,
    title      TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE persona (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    handle        TEXT NOT NULL,
    descriptor    TEXT,
    system_prompt TEXT NOT NULL,
    signature     TEXT
);

CREATE TABLE comment (
    id          TEXT PRIMARY KEY,
    thread_id   TEXT NOT NULL REFERENCES thread(id),
    parent_id   TEXT REFERENCES comment(id),
    author_id   TEXT NOT NULL,
    body        TEXT NOT NULL,
    state       TEXT NOT NULL,            -- DRAFTING | POSTED | FAILED | CANCELLED
    failure_category TEXT,                -- FAILED_RETRY | RATE_LIMITED | COULDNT_SAVE | ...
    reason      TEXT,                     -- user-facing one-line failure reason
    retry_after_seconds INTEGER,          -- populated only for RATE_LIMITED
    depth       INTEGER NOT NULL,
    created_at  TEXT NOT NULL
);
CREATE INDEX idx_comment_parent ON comment(parent_id);
CREATE INDEX idx_comment_thread ON comment(thread_id);

-- Owner +1 votes: stored with full attribution (the firewall is at the prompt boundary, not here).
CREATE TABLE vote (
    node_id  TEXT NOT NULL REFERENCES comment(id),
    voter_id TEXT NOT NULL,
    PRIMARY KEY (node_id, voter_id)
);

-- Append-only event log for replayability (§13).
CREATE TABLE event_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    type       TEXT NOT NULL,
    payload    TEXT NOT NULL,
    created_at TEXT NOT NULL
);
