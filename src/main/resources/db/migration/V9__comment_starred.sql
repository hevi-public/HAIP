-- Star a comment: an owner-only navigation bookmark (firewalled from the model, like +1). Stored as a
-- 0/1 flag so the branch index can mark a starred node and the thread can toggle it. SQLite has no
-- BOOLEAN type; INTEGER NOT NULL DEFAULT 0 keeps existing rows unstarred.
ALTER TABLE comment ADD COLUMN starred INTEGER NOT NULL DEFAULT 0;
