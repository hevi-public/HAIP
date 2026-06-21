package com.aiforum.repo

import com.aiforum.domain.Comment
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * JdbcTemplate persistence for the comment tree (see the sqlite-spring-jdbc skill — deliberately not
 * Hibernate). Branch context is read with the recursive-CTE ancestorPath; whole-thread context uses a
 * flat select.
 */
/** A recently-posted comment for the front-page rail; createdAt is the stored UTC ISO instant. */
data class RecentComment(
    val id: String,
    val threadId: String,
    val authorId: String,
    val body: String,
    val createdAt: String,
)

@Repository
class CommentRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    private val mapper = RowMapper { rs, _ ->
        Comment(
            id = rs.getString("id"),
            threadId = rs.getString("thread_id"),
            parentId = rs.getString("parent_id"),
            authorId = rs.getString("author_id"),
            body = rs.getString("body"),
            state = GenerationState.valueOf(rs.getString("state")),
            failureCategory = rs.getString("failure_category")?.let { FailureCategory.valueOf(it) },
            depth = rs.getInt("depth"),
            reason = rs.getString("reason"),
            retryAfterSeconds = rs.getObject("retry_after_seconds") as? Long
                ?: rs.getString("retry_after_seconds")?.toLongOrNull(),
            depthBudget = rs.getInt("depth_budget"),
        )
    }

    fun insert(c: Comment) {
        jdbc.update(
            """INSERT INTO comment(id, thread_id, parent_id, author_id, body, state, failure_category,
                                   reason, retry_after_seconds, depth, depth_budget, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            c.id, c.threadId, c.parentId, c.authorId, c.body, c.state.name, c.failureCategory?.name,
            c.reason, c.retryAfterSeconds, c.depth, c.depthBudget, clock.instant().toString(),
        )
    }

    fun update(c: Comment) {
        jdbc.update(
            """UPDATE comment SET body=?, state=?, failure_category=?, reason=?, retry_after_seconds=?
               WHERE id=?""",
            c.body, c.state.name, c.failureCategory?.name, c.reason, c.retryAfterSeconds, c.id,
        )
    }

    fun findById(id: String): Comment? =
        jdbc.query("SELECT * FROM comment WHERE id = ?", mapper, id).firstOrNull()

    fun threadComments(threadId: String): List<Comment> =
        jdbc.query("SELECT * FROM comment WHERE thread_id = ? ORDER BY depth, created_at", mapper, threadId)

    /**
     * The newest POSTED comments across all threads, for the front-page "Recent comments" rail. Drafts,
     * failures and cancelled nodes are excluded (only settled, visible replies). created_at is a UTC
     * ISO instant ('…Z'), so ORDER BY on the text column sorts chronologically.
     */
    fun recentPosted(limit: Int): List<RecentComment> =
        jdbc.query(
            """SELECT id, thread_id, author_id, body, created_at FROM comment
               WHERE state = 'POSTED' ORDER BY created_at DESC LIMIT ?""",
            { rs, _ ->
                RecentComment(
                    rs.getString("id"), rs.getString("thread_id"), rs.getString("author_id"),
                    rs.getString("body"), rs.getString("created_at"),
                )
            },
            limit,
        )

    /** Direct children of a node (its replies' siblings) — null parent = the thread's top-level nodes. */
    fun childrenOf(parentId: String?): List<Comment> =
        if (parentId == null)
            jdbc.query("SELECT * FROM comment WHERE parent_id IS NULL ORDER BY depth, created_at", mapper)
        else
            jdbc.query("SELECT * FROM comment WHERE parent_id = ? ORDER BY depth, created_at", mapper, parentId)

    /** Root → node ancestor path (branch-only scope) via recursive CTE. */
    fun ancestorPath(nodeId: String): List<Comment> =
        jdbc.query(
            """WITH RECURSIVE ancestors(id) AS (
                   SELECT id FROM comment WHERE id = ?
                   UNION ALL
                   SELECT c.parent_id FROM comment c JOIN ancestors a ON c.id = a.id WHERE c.parent_id IS NOT NULL
               )
               SELECT cm.* FROM comment cm JOIN ancestors an ON cm.id = an.id ORDER BY cm.depth""",
            mapper, nodeId,
        )

    /**
     * The autonomous-growth frontier (§4): POSTED leaf nodes that still have depth budget. A node can
     * sprout an auto-reply only if it has no children yet and budget > 0, so exhausted branches and
     * non-leaf nodes are excluded. FAILED/DRAFTING nodes are never grown under.
     */
    fun growableLeaves(threadId: String): List<Comment> =
        jdbc.query(
            """SELECT * FROM comment c
               WHERE c.thread_id = ?
                 AND c.state = 'POSTED'
                 AND c.depth_budget > 0
                 AND NOT EXISTS (SELECT 1 FROM comment k WHERE k.parent_id = c.id)
               ORDER BY c.depth, c.created_at""",
            mapper, threadId,
        )

    /** Number of descendants under [nodeId] (excluding the node itself) via recursive CTE. */
    fun descendantCount(nodeId: String): Int =
        jdbc.queryForObject(
            """WITH RECURSIVE sub(id) AS (
                   SELECT id FROM comment WHERE id = ?
                   UNION ALL
                   SELECT c.id FROM comment c JOIN sub s ON c.parent_id = s.id
               )
               SELECT COUNT(*) - 1 FROM sub""",
            Int::class.java, nodeId,
        ) ?: 0

    /**
     * Delete [nodeId] and its entire subtree (§8) — a deleted parent takes its replies with it, so no
     * child is ever left pointing at a gone parent. foreign_keys=on (every profile, see the datasource
     * URLs) means dependents must go first: `vote.node_id` and `comment.parent_id` both reference
     * `comment(id)`, so we clear the subtree's votes, then delete the comments deepest-first (a child is
     * always removed before its parent). Returns the ids removed; empty if [nodeId] doesn't exist.
     */
    fun deleteSubtree(nodeId: String): List<String> {
        val ids = subtreeIdsDeepestFirst(nodeId)
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        jdbc.update("DELETE FROM vote WHERE node_id IN ($placeholders)", *ids.toTypedArray())
        // Deepest-first so the self-referential parent_id FK is never momentarily violated.
        ids.forEach { jdbc.update("DELETE FROM comment WHERE id = ?", it) }
        return ids
    }

    /** Ids of [nodeId]'s subtree (itself + all descendants) ordered deepest depth first, for FK-safe delete. */
    private fun subtreeIdsDeepestFirst(nodeId: String): List<String> =
        jdbc.query(
            """WITH RECURSIVE sub(id, depth) AS (
                   SELECT id, depth FROM comment WHERE id = ?
                   UNION ALL
                   SELECT c.id, c.depth FROM comment c JOIN sub s ON c.parent_id = s.id
               )
               SELECT id FROM sub ORDER BY depth DESC""",
            { rs, _ -> rs.getString("id") }, nodeId,
        )
}
