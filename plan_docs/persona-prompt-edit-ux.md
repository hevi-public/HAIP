# Persona prompt editing — dials/prompt divergence & the Cancel flow

> **Status:** decided & built · **Owner:** Hevi · **Created:** 2026-06-22
> Companion to the persona-traits feature (`com.aiforum.persona`, V10) and `personas_admin.feature`.

## The problem

A persona's `system_prompt` is **composed from** its dials/abilities by a (paid) LLM call, but it is also
**editable and persisted in its own right**. On the edit form the prompt textarea is prefilled with the
current prompt. So if the owner moves a dial and clicks **Save** *without* regenerating, the saved prompt
no longer reflects the saved dials — they diverge silently. Composing on every save would avoid that but
makes every keystroke-then-save a paid call and throws away hand-edits.

Cancel made this sharper: there was **no Cancel control at all** — the only ways off the edit form were
Save (commit) or navigating away via the nav. The owner needs an explicit, safe "abandon my changes".

## Options considered

| # | Option | On Save | Divergence? | Effort | Trade-off |
|---|--------|---------|-------------|--------|-----------|
| 1 | Status quo — save-what-you-see, manual Regenerate | Persists the textarea as-is | Yes (silent) | none | Simplest; stale prompt saved with no signal |
| 2 | **Server resync** — recompose when dials changed but prompt untouched | If submitted prompt == stored prompt *and* inputs differ → recompose; else as-is | No (unless hand-edited) | low | Detectable with no JS/flags: an untouched textarea still equals the stored prompt. Respects hand-edits; no double-compose |
| 3 | Stale badge — flag divergence, don't prevent | Persists as-is, shows "dials changed since composed" | Yes (visible) | low–med | Honest without forcing a paid call; needs a composed-from snapshot |
| 4 | **Client nudge** — disable Save until Regenerate | Owner must regenerate (or hand-edit) before Save enables | No (nudged) | med | Best UX; pure JS, so not exercised by the HTTP-level acceptance suite |
| 5 | Two buttons — "Save as-is" vs "Regenerate & save" | Owner picks at save time | Owner's choice | low–med | Explicit; busier form |
| 6 | Always derive — drop the editable prompt | Always recompose; textarea read-only | No | med | Cleanest model, but kills hand-tweaking (the point of composed *prose*) |
| 7 | Declare prompt the source of truth | Dials are scaffolding; divergence by design | N/A | none | Stops treating dials as authoritative after composition |

## Decision — #4 (UX) layered over #2 (tested backstop)

- **#4 disable-Save-until-Regenerate is the owner-facing behaviour.** Changing a dial / abilities /
  descriptor marks the shown prompt **stale**: Save is disabled and **Regenerate is flagged** (a `⚠`
  icon + `is-needed` highlight) so the missing step is obvious. Regenerating — or hand-editing the prompt
  directly (the owner taking ownership of it) — clears stale and re-enables Save.
- **#2 server resync is the safety net**, and the part the suite actually tests. The disable in #4 is pure
  JS; with JS off (or a bypass) a stale prompt could still be submitted. So the controller, on edit,
  recomposes when the **submitted prompt equals the stored prompt** *and* a composer input changed — i.e.
  the owner moved a dial but never touched the prompt. A genuinely hand-edited prompt (submitted ≠ stored)
  is persisted as-is with **no** LLM call. A blank prompt composes (the one-shot path). This keeps the
  data honest regardless of the client and gives the acceptance suite teeth (the JS layer can't be driven
  by the HTTP-level Cucumber tests until a DOM driver is added).

Net: the owner is nudged to keep prompt and dials in sync; if they deliberately hand-edit, that's
respected; and a stale submit can never persist out of sync.

## Cancel

- **Edit form:** a **Cancel** link (`data-cancel-edit`) back to the persona profile (`/personas/{slug}`).
  Nothing is persisted until Save, so Cancel is a plain navigation — no state to roll back.
- **Create form** (inline on the members page): **Cancel** resets the form (`type="reset"`,
  `data-cancel-create`) to clear a half-authored persona.

## Layout

Actions sit in one row so the required step is obvious: **Cancel on the left**, then **Regenerate
immediately to the left of Save** on the right — so when Save is disabled, the adjacent flagged
Regenerate reads as "do this first". When stale, Regenerate carries a `⚠` and the `is-needed` highlight.

## Tests

- Acceptance (HTTP): the edit form offers a Cancel link to the profile; a stale submit (unchanged prompt +
  changed dials) re-composes; a hand-edited prompt persists with no composition call.
- **Frontend unit tier** (`src/test/js/persona-form-core.test.mjs`, run by the `jsTest` Gradle task in the
  pipeline): the staleness decision logic — `classifyField` / `reduceStale` / `shouldGate` — extracted into
  the pure `persona-form-core.mjs` (DOM glue in `persona-form.js`), the JS analogue of a Tier-0 unit. This
  covers *which* changes go stale and that the gate needs a shown prompt.
- **Still not covered (full DOM integration):** that the glue actually toggles `disabled`/`is-needed` on the
  real page — needs a browser/Playwright driver (see `HttpClient` KDoc). Manually verified for now.
