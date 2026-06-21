package com.aiforum.web

import com.aiforum.dto.Snippet
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadReadRepository
import com.aiforum.repo.ThreadRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Clock
import java.time.Instant

/** View model for a single row on the front page. */
data class ThreadRow(val id: String, val title: String, val unreadCount: Int)

/** A row in the right-rail "Active threads" box: thread + a compact "time ago" of its last activity. */
data class ActiveThreadRow(val id: String, val title: String, val ago: String)

/** A row in the right-rail "Recent comments" box: a quoted snippet linking to the comment in its thread. */
data class RecentCommentRow(val threadId: String, val id: String, val author: String, val snippet: String, val ago: String)

/**
 * Renders the front page (GET /): empty state when no threads exist; otherwise a list of thread
 * rows with unread reply counts (§2). The left rail's Members box lists the roster; the right rail's
 * Active-threads box lists the most recently active threads.
 */
@Controller
class HomeController(
    private val threads: ThreadRepository,
    private val threadReads: ThreadReadRepository,
    private val personas: PersonaRepository,
    private val comments: CommentRepository,
    private val clock: Clock,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        val rows = threads.findAll().map { t ->
            ThreadRow(t.id, t.title, threadReads.unreadCount(t.id))
        }
        model.addAttribute("threads", rows)
        val personaViews = personas.findAll().map {
            PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex)
        }
        model.addAttribute("personas", personaViews)
        // Left-rail "~/forum" nav counts.
        model.addAttribute("threadCount", rows.size)
        model.addAttribute("personaCount", personaViews.size)
        val now = clock.instant()
        model.addAttribute("activeThreads", threads.findActive(ACTIVE_THREADS_LIMIT).map { a ->
            ActiveThreadRow(a.id, a.title, RelativeTime.ago(Instant.parse(a.lastActivity), now))
        })
        model.addAttribute("recentComments", comments.recentPosted(RECENT_COMMENTS_LIMIT).map { c ->
            RecentCommentRow(c.threadId, c.id, c.authorId, Snippet.oneLine(c.body, RECENT_SNIPPET_LEN),
                RelativeTime.ago(Instant.parse(c.createdAt), now))
        })
        return "index"
    }

    private companion object {
        const val ACTIVE_THREADS_LIMIT = 5
        const val RECENT_COMMENTS_LIMIT = 5
        const val RECENT_SNIPPET_LEN = 64
    }
}
