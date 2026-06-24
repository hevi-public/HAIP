package com.aiforum.web

import com.aiforum.repo.RoutingEventRepository
import com.aiforum.service.RoutingOutcome
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One outcome row on the stats table: all-time and last-7-days counts for a single [RoutingOutcome]. */
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
 * Renders GET /admin/stats: routing-health numbers for the "Anyone" dispatcher
 * (plan_docs/persona-routing-observability.md). Read-only. The headline is the PARSE-MISS RATE —
 * `WIDENED_NO_MATCH / (MATCHED + WIDENED_NO_MATCH)` — which tells us how often name-matching silently
 * widened a decision the model may have meant to narrow.
 *
 * No auth model exists yet (the "owner" is implicit), so /admin is un-gated like the rest of the app;
 * this is an operator page, not end-user-facing — gate it when an owner/auth boundary actually lands.
 */
@Controller
class AdminController(
    private val routingEvents: RoutingEventRepository,
    private val clock: Clock,
) {
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

    private companion object {
        const val WINDOW_DAYS = 7L
        const val RECENT_MISS_LIMIT = 10

        // Fixed display order + human labels for the four outcomes (the enum stays the machine name).
        val OUTCOME_LABELS = linkedMapOf(
            RoutingOutcome.MATCHED to "Routed to a named pick",
            RoutingOutcome.WIDENED_NO_MATCH to "Widened — no name matched",
            RoutingOutcome.FAILED_GENERATION to "Generation failed",
            RoutingOutcome.SINGLE_PERSONA to "Single persona — no call",
        )
    }
}
