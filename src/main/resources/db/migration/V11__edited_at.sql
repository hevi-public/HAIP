-- Edit mode (§7): the owner can revise a posted comment body (their own or an AI persona's, to correct
-- context the model misread) and the thread's opening post (title + body). We track only WHEN a row was
-- last edited — no revision history — so a single nullable timestamp per row suffices. NULL = never
-- edited, which is what every existing row carries (so no backfill, and the "(edited)" marker stays off
-- until the owner actually changes something). Stored as TEXT (ISO-8601 UTC instant), matching created_at.
ALTER TABLE comment ADD COLUMN updated_at TEXT;
ALTER TABLE thread  ADD COLUMN updated_at TEXT;
