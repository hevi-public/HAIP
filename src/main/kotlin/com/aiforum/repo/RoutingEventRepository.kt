package com.aiforum.repo

import com.aiforum.dto.ScopeMode
import com.aiforum.service.RoutingMetrics
import com.aiforum.service.RoutingOutcome
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant

/**
 * The persisting adapter for [RoutingMetrics] (see the sqlite-spring-jdbc skill): each `pick()` outcome
 * lands in the `routing_event` table (V15), and the same class serves the count/recent-miss queries the
 * Admin → Statistics page reads. Storage keeps the full truth (incl. the raw routing reply for misses);
 * the firewall lives at the prompt boundary, not here.
 *
 * The injected [Clock] stamps `created_at`, so the fixed test clock makes the "last 7 days" window
 * deterministic (mirrors the rest of persistence).
 */
@Repository
class RoutingEventRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : RoutingMetrics {

    override fun record(
        outcome: RoutingOutcome,
        rosterSize: Int,
        pickedCount: Int,
        routingScope: ScopeMode,
        rawReply: String?,
    ) {
        jdbc.update(
            "INSERT INTO routing_event(outcome, roster_size, picked_count, routing_scope, raw_reply, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            outcome.name, rosterSize, pickedCount, routingScope.name, rawReply, clock.instant().toString(),
        )
    }

    /**
     * Count of each outcome, optionally restricted to events at or after [since] (the "last 7 days" window).
     * created_at is ISO-8601 UTC, so a lexicographic `>=` is a correct time comparison. Outcomes with no
     * rows are simply absent from the map — callers default them to 0.
     */
    fun counts(since: Instant? = null): Map<RoutingOutcome, Int> {
        val rows = if (since == null) {
            jdbc.query("SELECT outcome, COUNT(*) AS cnt FROM routing_event GROUP BY outcome", ::outcomeCount)
        } else {
            jdbc.query(
                "SELECT outcome, COUNT(*) AS cnt FROM routing_event WHERE created_at >= ? GROUP BY outcome",
                ::outcomeCount,
                since.toString(),
            )
        }
        return rows.toMap()
    }

    /** The most recent parse-miss routing replies, newest first — the eyeball-why list for the stats page. */
    fun recentMisses(limit: Int): List<RoutingMiss> =
        jdbc.query(
            "SELECT raw_reply, routing_scope, created_at FROM routing_event " +
                "WHERE outcome = 'WIDENED_NO_MATCH' ORDER BY created_at DESC, id DESC LIMIT ?",
            { rs, _ ->
                RoutingMiss(
                    reply = rs.getString("raw_reply") ?: "",
                    scope = rs.getString("routing_scope"),
                    createdAt = rs.getString("created_at"),
                )
            },
            limit,
        )

    private fun outcomeCount(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): Pair<RoutingOutcome, Int> =
        RoutingOutcome.valueOf(rs.getString("outcome")) to rs.getInt("cnt")

    /** A single parse-miss row for the stats page: the model's answer that named no one, plus when/scope. */
    data class RoutingMiss(val reply: String, val scope: String, val createdAt: String)
}
