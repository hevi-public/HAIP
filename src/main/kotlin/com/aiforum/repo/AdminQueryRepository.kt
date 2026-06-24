package com.aiforum.repo

import com.aiforum.dto.AdminAttachmentRow
import com.aiforum.dto.AdminCommentRow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

/**
 * The drill-down queries behind the admin dashboard's stat links: a stat figure links here with a
 * filter, and we list the matching comments (or attachments) so the owner can jump to each in its
 * thread. Read-only; newest first; capped at [CAP] (this is a single-user forum, so the cap only ever
 * bites pathological data — the list page surfaces a note when it does).
 */
@Repository
class AdminQueryRepository(private val jdbc: JdbcTemplate) {

    private val commentMapper = RowMapper { rs, _ ->
        AdminCommentRow(
            id = rs.getString("id"),
            threadId = rs.getString("thread_id"),
            threadTitle = rs.getString("thread_title"),
            authorId = rs.getString("author_id"),
            body = rs.getString("body"),
            state = rs.getString("state"),
            failureCategory = rs.getString("failure_category"),
            reasoningLeak = rs.getString("reasoning_leak"),
            votes = rs.getInt("votes"),
            createdAt = rs.getString("created_at"),
        )
    }

    private fun comments(where: String, order: String, vararg args: Any?): List<AdminCommentRow> {
        val clause = if (where.isBlank()) "" else "WHERE $where"
        val sql = """
            SELECT c.id AS id, c.thread_id AS thread_id, t.title AS thread_title, c.author_id AS author_id,
                   c.body AS body, c.state AS state, c.failure_category AS failure_category,
                   c.reasoning_leak AS reasoning_leak, c.created_at AS created_at,
                   (SELECT COUNT(*) FROM vote v WHERE v.node_id = c.id) AS votes
            FROM comment c JOIN thread t ON t.id = c.thread_id
            $clause ORDER BY $order LIMIT $CAP
        """.trimIndent()
        return jdbc.query(sql, commentMapper, *args)
    }

    fun allComments(): List<AdminCommentRow> = comments("", "c.created_at DESC")
    fun byState(state: String): List<AdminCommentRow> = comments("c.state = ?", "c.created_at DESC", state)
    fun byFailure(category: String): List<AdminCommentRow> = comments("c.failure_category = ?", "c.created_at DESC", category)
    fun byLeak(verdict: String): List<AdminCommentRow> = comments("c.reasoning_leak = ?", "c.created_at DESC", verdict)
    fun byAuthor(authorId: String): List<AdminCommentRow> = comments("c.author_id = ?", "c.created_at DESC", authorId)
    fun voted(): List<AdminCommentRow> =
        comments("EXISTS (SELECT 1 FROM vote v WHERE v.node_id = c.id)", "votes DESC, c.created_at DESC")
    fun regenerated(): List<AdminCommentRow> =
        comments("c.id IN (SELECT comment_id FROM comment_revision)", "c.created_at DESC")

    private val attachmentMapper = RowMapper { rs, _ ->
        AdminAttachmentRow(
            id = rs.getString("id"),
            ownerCommentId = rs.getString("comment_id"),
            ownerThreadId = rs.getString("owner_thread_id"),
            ownerThreadTitle = rs.getString("owner_thread_title"),
            mimeType = rs.getString("mime_type"),
            byteSize = rs.getLong("byte_size"),
            captionState = rs.getString("caption_state"),
            originalFilename = rs.getString("original_filename"),
        )
    }

    /** All attachments (optionally one caption state), each resolved to its owning thread for linking. */
    fun attachments(captionState: String? = null): List<AdminAttachmentRow> {
        val clause = if (captionState != null) "WHERE a.caption_state = ?" else ""
        val sql = """
            SELECT a.id AS id, a.comment_id AS comment_id, a.mime_type AS mime_type, a.byte_size AS byte_size,
                   a.caption_state AS caption_state, a.original_filename AS original_filename,
                   COALESCE(t1.id, t2.id) AS owner_thread_id, COALESCE(t1.title, t2.title) AS owner_thread_title
            FROM attachment a
            LEFT JOIN thread t1 ON t1.id = a.thread_id
            LEFT JOIN comment c ON c.id = a.comment_id
            LEFT JOIN thread t2 ON t2.id = c.thread_id
            $clause ORDER BY a.created_at DESC LIMIT $CAP
        """.trimIndent()
        return if (captionState != null) jdbc.query(sql, attachmentMapper, captionState)
        else jdbc.query(sql, attachmentMapper)
    }

    companion object {
        const val CAP = 200
    }
}
