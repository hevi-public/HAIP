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
        )
    }

    fun insert(c: Comment) {
        jdbc.update(
            """INSERT INTO comment(id, thread_id, parent_id, author_id, body, state, failure_category,
                                   reason, retry_after_seconds, depth, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            c.id, c.threadId, c.parentId, c.authorId, c.body, c.state.name, c.failureCategory?.name,
            c.reason, c.retryAfterSeconds, c.depth, clock.instant().toString(),
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
}
