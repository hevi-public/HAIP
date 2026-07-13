# Persona routing — observability & the Admin → Statistics page

> **Status:** ✅ built (2026-06-24) · **Owner:** Hevi · **Created:** 2026-06-21
> Shipped: `RoutingMetrics` write port + `RoutingOutcome` (four buckets) recorded once per
> `PersonaRouter.pick()`; V15 `routing_event` table via `RoutingEventRepository`; GET /admin/stats
> (parse-miss rate headline + per-outcome breakdown + recent-miss eyeball list) with a new "stats" nav item.
> Companion to the `PersonaRouter` KDoc and `src/test/resources/features/persona_routing.feature`.
> Concerns the "Anyone" dispatcher added in PR #22.

## Background — the failure mode this addresses

When the owner leaves the composer's "who answers" selector on its default **Anyone (the room decides)**,
the `PersonaRouter` asks the model which roster member(s) should reply, then recovers that decision by
**word-boundary string-matching roster names in the model's free-text reply**.

The decision is the model's, but it is honoured **only insofar as the model spells a roster member's
name**. If the model answers *"the backend folks should take this"* or *"ask the Kotlin person"* without
writing **"Sol"**, nothing matches and the router falls back to the **whole room** — silently widening a
choice the model may have meant to narrow. A thin slice of *"who decides"* is therefore really *"did the
model phrase it in a way we can parse."*

Today this is **invisible**: the fallback to "everyone" looks identical whether the model deliberately
chose everyone or we simply failed to parse its answer. We cannot tell how often it happens, so we cannot
judge whether the heavier fixes (numbered menu, descriptor matching, constrained/tool output, embedding
routing — see the `PersonaRouter` KDoc, ideas 2–6) are worth their cost.

## Recommended first step — make the fallback observable

**Before** investing in a more robust routing mechanism, instrument the one we have. This is the cheapest
idea on the list and it is the prerequisite for ranking the others: it turns "I imagine this misfires
sometimes" into a measured rate. Ship this first; let the data decide the rest.

### What to record

Every time `PersonaRouter.pick()` runs on the "Anyone" path, classify the outcome into exactly one bucket:

| Outcome | Meaning | Signal |
|---|---|---|
| `MATCHED` | the model named ≥1 roster member; we routed to that subset | routing is working |
| `WIDENED_NO_MATCH` | the model replied, but **no** roster name was found → fell back to the whole room | **the failure mode firing** |
| `FAILED_GENERATION` | the LLM call itself errored/timed out → fell back to the whole room | seam health, not parsing |
| `SINGLE_PERSONA` | roster size ≤ 1, so we skipped the LLM call entirely | baseline / denominator hygiene |

The headline metric is the **parse-miss rate** = `WIDENED_NO_MATCH / (MATCHED + WIDENED_NO_MATCH)`
(generation failures excluded — they are a different problem). A high rate justifies ideas 2–6; a near-zero
rate means the name-matching is fine and we can leave it.

Worth capturing alongside each event for later drill-down: roster size, number of personas picked,
`routingScope` (whole-topic vs this-branch), and a timestamp. Persisting the model's raw routing reply for
the `WIDENED_NO_MATCH` cases (behind a retention limit) would let us eyeball *why* matching missed — but
note the firewall (§7/§13): routing context already excludes the owner's `+1`, and this log must not
reintroduce any owner-identity signal.

### Where it lives (wiring)

Keep the seam discipline (see the bdd-tiered-testing skill): `PersonaRouter` already classifies the
outcome internally (it knows match vs. empty vs. threw), so the recording point is a single injected
collaborator — e.g. a `RoutingMetrics` sink — that `pick()` calls with the bucket. Two implementations:

- **Tier-1/prod:** a `routing_event` table (new Flyway migration `V_:_routing_event`) written via
  `JdbcTemplate`, consistent with the rest of persistence (see the sqlite-spring-jdbc skill). A counts
  query (`SELECT outcome, COUNT(*) ... GROUP BY outcome`, plus a windowed variant for "last 7 days")
  backs the stats page.
- **Test:** an in-memory fake sink so Tier-2 can assert "a prose reply naming no one records
  `WIDENED_NO_MATCH`" and "a clean pick records `MATCHED`" — directly pinning the failure-mode counter
  rather than inferring it.

This is a pure addition: `pick()`'s return value and the fallback behaviour are unchanged, so the existing
`PersonaRouterTest` cases and `persona_routing.feature` scenarios stay green.

## The Admin → Statistics page

Recording the metric is only useful if it is **visible**. Today the app has a two-item nav —
`threads` (`/`) and `members` (`/personas`) — and no admin area; persona creation lives on the members
page. Surfacing routing health calls for a small **Statistics** page under a new **Admin** area.

### Proposed shape

- **Route:** `GET /admin/stats` → new `AdminController` (or `StatsController`), rendering a new
  `admin/stats.kte` via JTE (see the jte-spring-kotlin skill). Read-only.
- **Nav:** add an `admin` (or directly `stats`) item to `layout.kte`'s `site-nav`, with the
  `aria-current` active-state treatment the existing links use (`activeNav == "admin"`).
- **Content (v1):** the routing-outcome breakdown —
  - the **parse-miss rate** as the headline number,
  - a small table of the four outcome counts (all-time and last-7-days),
  - optionally a short list of recent `WIDENED_NO_MATCH` routing replies for eyeballing.
- **DTO + assertion hooks:** a `RoutingStatsView` web DTO (mirrors the `PersonaView`/`ThreadRow` pattern),
  and stable `data-*` hooks (`data-stat="parse-miss-rate"`, `data-outcome="WIDENED_NO_MATCH"`, …) so an
  acceptance scenario can assert the page reflects seeded `routing_event` rows.

### Scope notes

- **Auth/visibility:** there is no real auth model yet (the "owner" is implicit). v1 can leave `/admin`
  un-gated like the rest of the app and revisit gating when an owner/auth boundary actually exists — but
  flag it so the page isn't mistaken for end-user-facing.
- **Room to grow:** once the page exists it is the natural home for other operational numbers (thread/
  reply counts, generation failure rates by `FailureCategory`, per-persona reply volume). Keep v1 to the
  routing metric so it ships small; design the DTO/table so adding sections later is cheap.

## Build order (when picked up)

1. `RoutingMetrics` sink + outcome classification in `PersonaRouter` (Tier-2 tests on the counters).
2. `routing_event` table + Flyway migration + counts queries (Tier-1).
3. `AdminController` + `admin/stats.kte` + nav item + `RoutingStatsView` (acceptance scenario on the
   data-* hooks over seeded rows).

Each step is independently shippable; step 1 alone already removes the "invisible" part of the failure
mode (the numbers exist in the DB even before the page renders them).
