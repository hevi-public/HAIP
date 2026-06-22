package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * Tracks per-thread read positions for the owner, used to compute unread reply counts on the home
 * page. Absent row = never read = all POSTED replies count as unread (§2).
 */
@Repository
class ThreadReadRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    fun markRead(threadId: String) {
        jdbc.update(
            """INSERT INTO thread_read(thread_id, last_read_at) VALUES (?,?)
               ON CONFLICT(thread_id) DO UPDATE SET last_read_at = excluded.last_read_at""",
            threadId, clock.instant().toString(),
        )
    }

    /** Drop the owner's read marker for a thread — called when the thread is deleted (the FK to
     *  thread(id) means this row must go before the thread row). No-op if there's no marker. */
    fun delete(threadId: String) {
        jdbc.update("DELETE FROM thread_read WHERE thread_id = ?", threadId)
    }

    fun unreadCount(threadId: String): Int {
        val lastReadAt = jdbc.query(
            "SELECT last_read_at FROM thread_read WHERE thread_id = ?",
            { rs, _ -> rs.getString("last_read_at") },
            threadId,
        ).firstOrNull()

        return if (lastReadAt == null) {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE thread_id = ? AND state = 'POSTED'",
                Int::class.java, threadId,
            ) ?: 0
        } else {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE thread_id = ? AND state = 'POSTED' AND created_at > ?",
                Int::class.java, threadId, lastReadAt,
            ) ?: 0
        }
    }
}
