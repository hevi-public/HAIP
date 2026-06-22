-- Image attachments on owner-authored nodes (the thread opening post, or a comment). Personas emit
-- text only, so an attachment always hangs off exactly one owner node — enforced by the CHECK: exactly
-- one of (thread_id, comment_id) is set. Bytes live on disk under aiforum.images.dir (content-addressed
-- by sha256); this table holds only the metadata + the disk path.
--
-- caption is the vision-model description, generated MANUALLY (owner clicks "Describe") and injected
-- into LLM context as text — the universal, caption-only path (raw bytes never reach generation models).
-- caption_state mirrors the comment generation lifecycle (NONE -> DESCRIBING -> DESCRIBED|FAILED) so the
-- describe flow reuses the same mental model. NULL caption = not described yet.
CREATE TABLE attachment (
    id                TEXT PRIMARY KEY,
    thread_id         TEXT REFERENCES thread(id),
    comment_id        TEXT REFERENCES comment(id),
    sha256            TEXT NOT NULL,
    storage_path      TEXT NOT NULL,            -- relative to the images dir, derived from sha256
    mime_type         TEXT NOT NULL,            -- image/png | image/jpeg | image/gif | image/webp
    byte_size         INTEGER NOT NULL,
    original_filename TEXT,
    caption           TEXT,                     -- vision-model output; NULL until described
    caption_model     TEXT,
    caption_state     TEXT NOT NULL DEFAULT 'NONE',
    sort_order        INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL,
    -- (thread_id IS NOT NULL) and (comment_id IS NOT NULL) each evaluate to 0/1, so <> is XOR: exactly
    -- one owner. An attachment can never be orphaned (both null) or double-owned (both set).
    CHECK ((thread_id IS NOT NULL) <> (comment_id IS NOT NULL))
);

CREATE INDEX idx_attachment_thread ON attachment(thread_id);
CREATE INDEX idx_attachment_comment ON attachment(comment_id);
