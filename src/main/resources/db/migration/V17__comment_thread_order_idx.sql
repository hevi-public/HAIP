-- Composite index for the dominant comment read. (V17 follows V16 = persona_slug_unique.)
-- threadComments and growableLeaves (CommentRepository) both filter `WHERE thread_id = ?` and then
-- `ORDER BY depth, created_at`. The V1 idx_comment_thread (thread_id alone) serves the filter but leaves
-- SQLite to sort the matched rows in a temp B-tree on every read. Matching the index key order to the
-- query's (equality column first, then the two ORDER BY columns) lets the planner walk the index in
-- already-sorted order — the row set comes out ordered with no separate sort step. Pure perf: no schema
-- or behaviour change, so there's no backfill and no acceptance test, just a faster hot path.

CREATE INDEX idx_comment_thread_order ON comment(thread_id, depth, created_at);
