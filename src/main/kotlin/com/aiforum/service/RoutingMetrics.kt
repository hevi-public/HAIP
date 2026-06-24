package com.aiforum.service

import com.aiforum.dto.ScopeMode

/**
 * The four mutually-exclusive outcomes of one "Anyone" routing decision (see
 * plan_docs/persona-routing-observability.md). The headline metric is the PARSE-MISS RATE =
 * `WIDENED_NO_MATCH / (MATCHED + WIDENED_NO_MATCH)` — generation failures are excluded because they're a
 * seam-health problem, not a parsing one, and a lone roster needs no decision at all.
 */
enum class RoutingOutcome {
    /** The model named ≥1 roster member; we routed to that subset. Routing is working. */
    MATCHED,

    /** The model replied but spelled no roster name → we fell back to the whole room. The failure mode. */
    WIDENED_NO_MATCH,

    /** The LLM call itself errored/timed out → fell back to the whole room. Seam health, not parsing. */
    FAILED_GENERATION,

    /** Roster size ≤ 1, so we skipped the LLM call entirely. Baseline / denominator hygiene. */
    SINGLE_PERSONA,
}

/**
 * Where the [PersonaRouter] records each routing outcome. A pure addition: the router classifies the
 * outcome it already knows (match vs. empty vs. threw) and calls this once per `pick()`, so the routing
 * behaviour is unchanged and the failure mode stops being invisible.
 *
 * Kept a narrow WRITE port so the router depends only on "somewhere to record an event" — the prod
 * adapter (RoutingEventRepository) persists to SQLite; the Tier-2 tests use a tiny capturing fake.
 */
interface RoutingMetrics {
    fun record(
        outcome: RoutingOutcome,
        rosterSize: Int,
        pickedCount: Int,
        routingScope: ScopeMode,
        // The model's raw routing reply — captured only for WIDENED_NO_MATCH so we can eyeball *why*
        // matching missed; null otherwise. Firewall-safe: it's the model's own text naming personas, and
        // the routing context already excludes the owner's +1 (§7/§13), so no owner-identity signal leaks.
        rawReply: String?,
    )
}

/** The default sink: drops everything. Lets the Tier-2 `PersonaRouter(llm)` construction stay metrics-free. */
object NoOpRoutingMetrics : RoutingMetrics {
    override fun record(
        outcome: RoutingOutcome,
        rosterSize: Int,
        pickedCount: Int,
        routingScope: ScopeMode,
        rawReply: String?,
    ) = Unit
}
