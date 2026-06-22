-- Content versioning for AI replies (§7). (V14 follows V13 = image attachments.)
-- A regenerate OR an owner edit APPENDS a new revision instead of overwriting, so the owner can step back
-- through earlier takes — the node shows a "2/3" indicator and a ‹ › switcher. `comment.body` stays the
-- denormalised CURRENT revision (so every context-assembly / markdown / rail query keeps reading it
-- untouched); `comment.revision_index` records which revision is currently shown.
--
-- Lazily materialised: a never-versioned comment has ZERO rows here and an implicit single revision (its
-- own body). The FIRST regenerate/edit seeds idx 0 = the body being replaced, then idx 1 = the new take;
-- from then on this table is authoritative for that comment's history. So a row count of 0 means "1 of 1".

CREATE TABLE comment_revision (
    comment_id     TEXT    NOT NULL REFERENCES comment(id),
    idx            INTEGER NOT NULL,            -- 0-based position; comment.revision_index selects one
    body           TEXT    NOT NULL,
    reasoning_leak TEXT,                        -- per-revision leak verdict (mirrors comment.reasoning_leak)
    -- When this revision is an OWNER EDIT, the edit timestamp; NULL for a generated take (original/regen).
    -- selectRevision copies this into comment.updated_at, so the "(edited)" marker tracks the shown version.
    edited_at      TEXT,
    created_at     TEXT    NOT NULL,
    PRIMARY KEY (comment_id, idx)
);
CREATE INDEX idx_comment_revision_comment ON comment_revision(comment_id);

-- Which revision the node currently shows (0-based; 0 for every existing/un-regenerated comment).
ALTER TABLE comment ADD COLUMN revision_index INTEGER NOT NULL DEFAULT 0;
