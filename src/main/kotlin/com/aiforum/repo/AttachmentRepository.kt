package com.aiforum.repo

import com.aiforum.domain.Attachment
import com.aiforum.domain.CaptionState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * JdbcTemplate persistence for image attachments (see the sqlite-spring-jdbc skill — deliberately not
 * Hibernate). An attachment hangs off exactly one owner node (thread OP or comment, the V13 CHECK).
 * [forComments] is the batch read the context assembler uses to fold captions into the prompt.
 */
@Repository
class AttachmentRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    private val mapper = RowMapper { rs, _ ->
        Attachment(
            id = rs.getString("id"),
            threadId = rs.getString("thread_id"),
            commentId = rs.getString("comment_id"),
            sha256 = rs.getString("sha256"),
            storagePath = rs.getString("storage_path"),
            mimeType = rs.getString("mime_type"),
            byteSize = rs.getLong("byte_size"),
            originalFilename = rs.getString("original_filename"),
            caption = rs.getString("caption"),
            captionModel = rs.getString("caption_model"),
            captionState = CaptionState.valueOf(rs.getString("caption_state")),
            sortOrder = rs.getInt("sort_order"),
        )
    }

    fun insert(a: Attachment) {
        jdbc.update(
            """INSERT INTO attachment(id, thread_id, comment_id, sha256, storage_path, mime_type,
                                      byte_size, original_filename, caption, caption_model, caption_state,
                                      sort_order, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            a.id, a.threadId, a.commentId, a.sha256, a.storagePath, a.mimeType, a.byteSize,
            a.originalFilename, a.caption, a.captionModel, a.captionState.name, a.sortOrder,
            clock.instant().toString(),
        )
    }

    fun find(id: String): Attachment? =
        jdbc.query("SELECT * FROM attachment WHERE id = ?", mapper, id).firstOrNull()

    fun forThread(threadId: String): List<Attachment> =
        jdbc.query("SELECT * FROM attachment WHERE thread_id = ? ORDER BY sort_order, created_at", mapper, threadId)

    fun forComment(commentId: String): List<Attachment> =
        jdbc.query("SELECT * FROM attachment WHERE comment_id = ? ORDER BY sort_order, created_at", mapper, commentId)

    /**
     * Batch read for context assembly: every attachment whose owner comment is in [commentIds], grouped
     * by comment id. Empty in → empty map (no SQL, avoids an `IN ()` syntax error).
     */
    fun forComments(commentIds: Collection<String>): Map<String, List<Attachment>> {
        if (commentIds.isEmpty()) return emptyMap()
        val placeholders = commentIds.joinToString(",") { "?" }
        return jdbc.query(
            "SELECT * FROM attachment WHERE comment_id IN ($placeholders) ORDER BY sort_order, created_at",
            mapper, *commentIds.toTypedArray(),
        ).groupBy { it.commentId!! }
    }

    /** Store a freshly-generated caption (the manual describe succeeded) and flip the state. */
    fun updateCaption(id: String, caption: String, model: String, state: CaptionState): Boolean =
        jdbc.update(
            "UPDATE attachment SET caption = ?, caption_model = ?, caption_state = ? WHERE id = ?",
            caption, model, state.name, id,
        ) > 0

    /** Move an attachment's describe lifecycle without touching the caption (DESCRIBING / FAILED). */
    fun setState(id: String, state: CaptionState): Boolean =
        jdbc.update("UPDATE attachment SET caption_state = ? WHERE id = ?", state.name, id) > 0

    fun delete(id: String) {
        jdbc.update("DELETE FROM attachment WHERE id = ?", id)
    }

    /** True if any other attachment still references [sha256] — so a dedup-aware blob GC can decide. */
    fun shaReferenced(sha256: String, exceptId: String): Boolean =
        (jdbc.queryForObject(
            "SELECT COUNT(*) FROM attachment WHERE sha256 = ? AND id <> ?", Int::class.java, sha256, exceptId,
        ) ?: 0) > 0
}
