-- Tracks when the owner last read each thread, for computing the unread reply count (§2).
-- No row for a thread means it has never been read → all POSTED replies are unread.
CREATE TABLE thread_read (
    thread_id   TEXT PRIMARY KEY REFERENCES thread(id),
    last_read_at TEXT NOT NULL
);
