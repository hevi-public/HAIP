-- Comment quotes — a directed citation edge between two comments (see plan_docs/comment-quotes.md).
-- (V18 follows V17 = comment_thread_order_idx.)
--
-- When the owner quotes a passage of one comment into a reply, we record an edge: `src` (the quoting
-- comment, which carries the markdown blockquote) cites `target` (the quoted comment). `quoted_text` is a
-- verbatim SNAPSHOT of the selected span at quote time — the link is anchored to the snapshot + the target
-- COMMENT, not a live character range, so it survives the source being edited/regenerated (V14). A
-- hand-typed `> blockquote` carries no edge and is just markdown; everything is derived from edge presence
-- (no manual-vs-menu "type" column — see the design note §5).
--
-- thread_id is denormalised so ReplyTreeAssembler can read a whole thread's edges in one batch query,
-- the same way it batch-reads votes / revisions / attachments.

CREATE TABLE comment_quote (
    id                TEXT PRIMARY KEY,
    thread_id         TEXT NOT NULL,
    src_comment_id    TEXT NOT NULL,   -- the QUOTING comment (carries the blockquote)
    target_comment_id TEXT NOT NULL,   -- the QUOTED comment (the source of the text)
    quoted_text       TEXT NOT NULL,   -- verbatim snapshot of the quoted span
    created_at        TEXT NOT NULL,
    -- ON DELETE CASCADE on BOTH ends: deleting a comment (deleteSubtree cascades the tree) removes any
    -- edge it is an endpoint of, as quoter or quotee. This also means a stray edge never FK-blocks a
    -- `DELETE FROM comment`, so the tier-1 isolation trap that bit attachment (a RESTRICT FK) can't recur.
    FOREIGN KEY (src_comment_id)    REFERENCES comment(id) ON DELETE CASCADE,
    FOREIGN KEY (target_comment_id) REFERENCES comment(id) ON DELETE CASCADE
);

CREATE INDEX idx_comment_quote_src    ON comment_quote(src_comment_id);
CREATE INDEX idx_comment_quote_target ON comment_quote(target_comment_id);
