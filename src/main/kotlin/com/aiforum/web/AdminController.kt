package com.aiforum.web

import com.aiforum.dto.AdminAttachmentRow
import com.aiforum.dto.AdminCommentRow
import com.aiforum.dto.Snippet
import com.aiforum.repo.AdminQueryRepository
import com.aiforum.repo.RoutingEventRepository
import com.aiforum.repo.StatsRepository
import com.aiforum.service.RoutingOutcome
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.Duration
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

/** One outcome row on the routing stats table: all-time and last-7-days counts for a [RoutingOutcome]. */
data class OutcomeStat(
    val outcome: String,
    val label: String,
    val allTime: Int,
    val last7Days: Int,
)

/** A recent parse-miss for the eyeball list: the model's answer that named no one. */
data class RoutingMissView(
    val reply: String,
    val scope: String,
    val ago: String,
)

/** The /admin/stats page model — the routing-outcome breakdown plus the headline parse-miss rate. */
data class RoutingStatsView(
    // Pre-formatted "NN%" (or "—" when there's no MATCHED+WIDENED denominator yet).
    val parseMissRate: String,
    val hasParseMissRate: Boolean,
    val outcomes: List<OutcomeStat>,
    val recentMisses: List<RoutingMissView>,
)

/**
 * The admin pages — all read-only, all public (the app is single-owner/local-first with no auth layer
 * yet; gate /admin when an owner/auth boundary actually lands):
 *  - GET /admin                  — the statistics dashboard, each figure linking to the items behind it.
 *  - GET /admin/comments         — a filtered comment list (the dashboard's comment/vote/leak drill-downs).
 *  - GET /admin/attachments      — the attachment list (the dashboard's attachment drill-downs).
 *  - GET /admin/stats            — routing-health for the "Anyone" dispatcher
 *                                  (plan_docs/persona-routing-observability.md): the per-outcome breakdown
 *                                  plus the headline PARSE-MISS RATE,
 *                                  `WIDENED_NO_MATCH / (MATCHED + WIDENED_NO_MATCH)` — how often
 *                                  name-matching silently widened a decision the model may have narrowed.
 */
@Controller
class AdminController(
    private val stats: StatsRepository,
    private val query: AdminQueryRepository,
    private val routingEvents: RoutingEventRepository,
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

    @GetMapping("/admin/stats")
    fun stats(model: Model): String {
        val now = clock.instant()
        val allTime = routingEvents.counts()
        val last7Days = routingEvents.counts(now.minus(Duration.ofDays(WINDOW_DAYS)))

        val matched = allTime[RoutingOutcome.MATCHED] ?: 0
        val widened = allTime[RoutingOutcome.WIDENED_NO_MATCH] ?: 0
        val denominator = matched + widened
        val hasRate = denominator > 0
        val rate = if (hasRate) "${Math.round(widened * 100.0 / denominator)}%" else "—"

        val outcomes = OUTCOME_LABELS.map { (outcome, label) ->
            OutcomeStat(outcome.name, label, allTime[outcome] ?: 0, last7Days[outcome] ?: 0)
        }
        val recentMisses = routingEvents.recentMisses(RECENT_MISS_LIMIT).map {
            RoutingMissView(it.reply, it.scope, RelativeTime.ago(Instant.parse(it.createdAt), now))
        }

        model.addAttribute("stats", RoutingStatsView(rate, hasRate, outcomes, recentMisses))
        return "admin/stats"
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

        const val WINDOW_DAYS = 7L
        const val RECENT_MISS_LIMIT = 10

        // Fixed display order + human labels for the four routing outcomes (the enum stays the machine name).
        val OUTCOME_LABELS = linkedMapOf(
            RoutingOutcome.MATCHED to "Routed to a named pick",
            RoutingOutcome.WIDENED_NO_MATCH to "Widened — no name matched",
            RoutingOutcome.FAILED_GENERATION to "Generation failed",
            RoutingOutcome.SINGLE_PERSONA to "Single persona — no call",
        )
    }
}
