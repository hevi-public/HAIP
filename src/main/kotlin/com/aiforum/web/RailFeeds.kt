package com.aiforum.web

import com.aiforum.dto.Snippet
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.ThreadRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * The forum-wide rail feeds — "Active threads" and "Recent comments" — shared by the home page's right
 * rail and every thread page's right rail. Centralised here so the two surfaces can't drift in count,
 * snippet length, or "time ago" formatting: the feeds read identically wherever they appear.
 */
@Component
class RailFeeds(
    private val threads: ThreadRepository,
    private val comments: CommentRepository,
    private val clock: Clock,
) {
    /** The most-recently-active threads, newest first, with a compact "time ago" of their last activity. */
    fun activeThreads(): List<ActiveThreadRow> {
        val now = clock.instant()
        return threads.findActive(ACTIVE_THREADS_LIMIT).map { a ->
            ActiveThreadRow(a.id, a.title, RelativeTime.ago(Instant.parse(a.lastActivity), now))
        }
    }

    /** The newest posted comments across all threads, each a quoted snippet linking back to its thread. */
    fun recentComments(): List<RecentCommentRow> {
        val now = clock.instant()
        return comments.recentPosted(RECENT_COMMENTS_LIMIT).map { c ->
            RecentCommentRow(
                c.threadId, c.id, c.authorId, Snippet.oneLine(c.body, RECENT_SNIPPET_LEN),
                RelativeTime.ago(Instant.parse(c.createdAt), now),
            )
        }
    }

    private companion object {
        const val ACTIVE_THREADS_LIMIT = 5
        const val RECENT_COMMENTS_LIMIT = 5
        const val RECENT_SNIPPET_LEN = 64
    }
}
