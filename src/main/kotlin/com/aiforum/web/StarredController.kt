package com.aiforum.web

import com.aiforum.dto.Snippet
import com.aiforum.repo.CommentRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Clock
import java.time.Instant

/** A thread section on the /starred page: the thread title + all its starred comments. */
data class StarredThreadSection(
    val threadId: String,
    val threadTitle: String,
    val items: List<StarredPageItem>,
)

/** A single starred comment row on the /starred page. */
data class StarredPageItem(
    val id: String,
    val authorId: String,
    val snippet: String,
    val ago: String,
)

/**
 * Renders GET /starred: all starred POSTED comments across every thread, grouped by thread.
 * This is the "See all" destination linked from the starred rail box on the home and thread pages.
 */
@Controller
class StarredController(
    private val comments: CommentRepository,
    private val clock: Clock,
) {
    @GetMapping("/starred")
    fun starred(model: Model): String {
        val now = clock.instant()
        val sections = comments.allStarredPosted()
            .groupBy { it.threadId }
            .map { (threadId, items) ->
                StarredThreadSection(
                    threadId = threadId,
                    threadTitle = items.first().threadTitle,
                    items = items.map { c ->
                        StarredPageItem(
                            id = c.id,
                            authorId = c.authorId,
                            snippet = Snippet.oneLine(c.body, SNIPPET_LEN),
                            ago = RelativeTime.ago(Instant.parse(c.createdAt), now),
                        )
                    },
                )
            }
        model.addAttribute("sections", sections)
        model.addAttribute("isEmpty", sections.isEmpty())
        return "starred"
    }

    private companion object {
        const val SNIPPET_LEN = 80
    }
}
