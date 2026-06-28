package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock
import java.util.UUID

/** A persisted quote edge: [srcCommentId] cites [targetCommentId], snapshotting [quotedText]. */
data class QuoteEdge(
    val id: String,
    val threadId: String,
    val srcCommentId: String,
    val targetCommentId: String,
    val quotedText: String,
)

/**
 * Quote edges between comments (see plan_docs/comment-quotes.md). A directed graph laid over the reply
 * tree: an edge means the `src` comment quotes the `target` comment. The injected [Clock] stamps
 * `created_at`, matching the other repositories. Edge rows FK-cascade with their endpoint comments, so
 * there is no delete method here — removing a comment removes its edges.
 */
@Repository
class QuoteRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    /**
     * Record one quote edge (`src` cites `target`). The caller validates that the target exists and lives
     * in the same thread, and de-dupes, before calling — this just writes the row.
     */
    fun insert(threadId: String, srcCommentId: String, targetCommentId: String, quotedText: String) {
        jdbc.update(
            """INSERT INTO comment_quote(id, thread_id, src_comment_id, target_comment_id, quoted_text, created_at)
               VALUES (?,?,?,?,?,?)""",
            UUID.randomUUID().toString(), threadId, srcCommentId, targetCommentId, quotedText,
            clock.instant().toString(),
        )
    }

    /**
     * Every edge in [threadId] grouped by its `src` comment id — one batch read the assembler folds into
     * each node's forward "quotes" strip (oldest first, so multiple quotes render in the order they were
     * made). src and target always share a thread (a quote is within a thread), so thread_id is exact.
     */
    fun bySource(threadId: String): Map<String, List<QuoteEdge>> =
        edgesIn(threadId).groupBy { it.srcCommentId }

    /**
     * Every edge in [threadId] grouped by its `target` comment id — the backward direction the assembler
     * folds into each node's "quoted by" backlinks (oldest first). Same single read as [bySource], grouped
     * the other way.
     */
    fun byTarget(threadId: String): Map<String, List<QuoteEdge>> =
        edgesIn(threadId).groupBy { it.targetCommentId }

    // One ordered read of a thread's edges, shared by bySource/byTarget. rowid is the deterministic
    // tiebreak after created_at: it increments with insertion (so it agrees with chronological order in
    // prod) AND breaks ties when many edges share a timestamp — which they do under the fixed test clock,
    // where created_at alone wouldn't be stable.
    private fun edgesIn(threadId: String): List<QuoteEdge> =
        jdbc.query(
            """SELECT id, thread_id, src_comment_id, target_comment_id, quoted_text
               FROM comment_quote WHERE thread_id = ? ORDER BY created_at, rowid""",
            { rs, _ ->
                QuoteEdge(
                    rs.getString("id"), rs.getString("thread_id"), rs.getString("src_comment_id"),
                    rs.getString("target_comment_id"), rs.getString("quoted_text"),
                )
            },
            threadId,
        )
}
