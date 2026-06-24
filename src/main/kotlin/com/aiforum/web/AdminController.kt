package com.aiforum.web

import com.aiforum.dto.AdminAttachmentRow
import com.aiforum.dto.AdminCommentRow
import com.aiforum.dto.Snippet
import com.aiforum.repo.AdminQueryRepository
import com.aiforum.repo.StatsRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.Instant

/** A comment row on an /admin/comments drill-down: a one-line snippet linking to its thread permalink. */
data class AdminCommentView(
    val id: String,
    val threadId: String,
    val threadTitle: String,
    val authorId: String,
    val snippet: String,
    val state: String,
    val failureCategory: String?,
    val reasoningLeak: String?,
    val votes: Int,
    val ago: String,
)

/** An attachment row on the /admin/attachments drill-down, linking to the image and its owner thread. */
data class AdminAttachmentView(
    val id: String,
    val ownerThreadId: String?,
    val ownerThreadTitle: String?,
    val ownerCommentId: String?,
    val mimeType: String,
    val byteSize: Long,
    val captionState: String,
    val filename: String?,
)

/**
 * The admin dashboard (GET /admin) and its read-only drill-downs (GET /admin/comments, /admin/attachments).
 * Public, like every other page — the app is single-owner/local-first with no auth layer (a deliberate
 * decision for this slice). Nothing here mutates; the drill-downs only filter and list.
 */
@Controller
class AdminController(
    private val stats: StatsRepository,
    private val query: AdminQueryRepository,
    private val clock: Clock,
) {

    @GetMapping("/admin")
    fun admin(model: Model): String {
        model.addAttribute("stats", stats.snapshot())
        return "admin"
    }

    @GetMapping("/admin/comments")
    fun comments(
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) failure: String?,
        @RequestParam(required = false) leak: String?,
        @RequestParam(required = false) author: String?,
        @RequestParam(defaultValue = "false") voted: Boolean,
        @RequestParam(defaultValue = "false") regenerated: Boolean,
        model: Model,
    ): String {
        val now = clock.instant()
        val (title, rows) = when {
            state != null -> "Comments · $state" to query.byState(state)
            failure != null -> "Comments · failure: $failure" to query.byFailure(failure)
            leak != null -> "Comments · reasoning leak: $leak" to query.byLeak(leak)
            author != null -> "Comments by $author" to query.byAuthor(author)
            voted -> "Comments with +1 votes" to query.voted()
            regenerated -> "Regenerated / edited comments" to query.regenerated()
            else -> "All comments" to query.allComments()
        }
        model.addAttribute("listTitle", title)
        model.addAttribute("rows", rows.map { it.toView(now) })
        model.addAttribute("capped", rows.size >= AdminQueryRepository.CAP)
        model.addAttribute("cap", AdminQueryRepository.CAP)
        return "admin_comments"
    }

    @GetMapping("/admin/attachments")
    fun attachments(
        @RequestParam(required = false) caption: String?,
        model: Model,
    ): String {
        val rows = query.attachments(caption)
        model.addAttribute("listTitle", if (caption != null) "Attachments · $caption" else "Attachments")
        model.addAttribute("rows", rows.map { it.toView() })
        model.addAttribute("capped", rows.size >= AdminQueryRepository.CAP)
        model.addAttribute("cap", AdminQueryRepository.CAP)
        return "admin_attachments"
    }

    private fun AdminCommentRow.toView(now: Instant) = AdminCommentView(
        id = id,
        threadId = threadId,
        threadTitle = threadTitle,
        authorId = authorId,
        snippet = Snippet.oneLine(body, SNIPPET_LEN),
        state = state,
        failureCategory = failureCategory,
        reasoningLeak = reasoningLeak,
        votes = votes,
        ago = RelativeTime.ago(Instant.parse(createdAt), now),
    )

    private fun AdminAttachmentRow.toView() = AdminAttachmentView(
        id = id,
        ownerThreadId = ownerThreadId,
        ownerThreadTitle = ownerThreadTitle,
        ownerCommentId = ownerCommentId,
        mimeType = mimeType,
        byteSize = byteSize,
        captionState = captionState,
        filename = originalFilename,
    )

    private companion object {
        const val SNIPPET_LEN = 100
    }
}
