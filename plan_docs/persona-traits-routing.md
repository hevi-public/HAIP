# Persona traits as routing signal — abilities & dials in the "Anyone" dispatcher

> **Status:** ✅ built (2026-06-24) · **Owner:** Hevi · **Created:** 2026-06-21
> Built as prompt-enrichment over the dispatcher's existing LLM call (NOT a deterministic keyword overlap —
> topic→ability matching is a semantic judgement the model does better): `PersonaRouter.rosterLine` now folds
> each persona's abilities + off-centre dials into the roster the model sees, and `PersonaRouter.diversify`
> re-ranks a capped fan-out to span the agreeableness axis (pure Tier-0). The single `LlmClient` seam is
> unchanged. Observability (the prereq below) shipped alongside, so the parse-miss rate is now measurable.
> Companion to the `PersonaRouter` KDoc, `src/test/resources/features/persona_routing.feature`, and the
> persona-traits feature (abilities + dials + LLM-composed prompt, V10 — see `com.aiforum.persona`).
> Related: `plan_docs/persona-routing-observability.md` (instrument the fallback first).

## Background — we now have structured personality the router ignores

Personas gained two structured fields (V10): **abilities** (open-vocabulary keyword tags — `kotlin`,
`backend`, `history`) and **dials** (a fixed 0–10 schema: agreeableness, verbosity, rigor, warmth —
`com.aiforum.persona.Dials.KEYS`). Today these feed only the *composed system prompt*; the **`PersonaRouter`
still routes purely on the model naming roster members in free text**, then word-boundary-matches those
names (see the KDoc's "known failure mode").

That leaves the payoff of making traits structured unrealised. The whole reason to lift personality out of
prose and into comparable fields was so the *system* — not just the generation model — can reason over it.
The router is exactly that consumer.

## The two wins

### 1. Abilities → sharper topic matching (a better version of KDoc idea 3)

The KDoc lists "match descriptors too, not just names" (idea 3) as a forgiving fallback when no name hits,
and "embedding-based routing" (idea 6) as the parse-free deterministic option. **Abilities are a cleaner
signal than either**: a bounded, owner-curated tag set means topic→persona matching can be a deterministic
keyword/overlap score (topic terms ∩ persona abilities) with no embedding model and far fewer false
positives than free-text descriptor matching. Use it as a **prior / tie-breaker** layered on the model's
pick, and as the **fallback** when name-matching misses — instead of silently widening to the whole room
(the failure mode the observability note measures).

### 2. Dials → deliberately *complementary* picks, not N of the same voice

When "Anyone" fans out to several personas, today nothing stops it from picking three who'd all say the
same thing. Dials make **diversity of pick** an explicit objective: prefer a set that spans the
`agreeableness` axis (a contrarian *and* an agreeable builder) so a thread reads like a room with friction,
not a chorus. This is unique to the forum format and is the single most interesting use of the structured
data — it changes the *texture* of a thread, not just who shows up.

`verbosity`/`rigor` could also feed downstream generation knobs later (clamp `max-tokens`, inject
"cite your reasoning"), but that's generation, not routing — out of scope for this note.

## Sketch (when picked up)

- Keep the single `LlmClient` seam. This is a **post-processing layer over the existing pick**, not a new
  IO boundary: the model still proposes; abilities provide the prior/fallback; dials re-rank the final set
  for spread. A pure scoring function (Tier 0) over `(topicTerms, roster, modelPick)` → ordered subset
  keeps the logic unit-testable without an LLM.
- **Order it after observability.** Ship `plan_docs/persona-routing-observability.md` first so we can
  measure the parse-miss rate *before* and *after* — that's how we prove abilities-as-fallback actually
  reduced the silent widening, rather than guessing.
- Respect the firewall (§7/§13): routing context already excludes the owner's `+1`; trait-based scoring
  reads persona fields only, introduces no owner-identity signal.

## Build order

1. (Prereq) Routing observability — measure the baseline parse-miss rate.
2. Tier-0 scoring function: abilities overlap → topic-match score; dials → spread score over a candidate set.
3. Wire it into `PersonaRouter.pick()` as prior + fallback + diversity re-rank; extend `PersonaRouterTest`
   (abilities fallback fires instead of whole-room widening; a fan-out spans the agreeableness axis).
4. Re-measure the parse-miss rate to confirm the win.
