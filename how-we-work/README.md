# How we test & organise HAIP

> **Status:** ✅ written 2026-07-10 · **Owner:** Hevi · every number below was grep-derived and then
> independently re-derived by a second pass; claims that didn't survive adversarial verification were
> corrected before landing here.
>
> 🖼 **The one-page version:** open [`infographic.html`](infographic.html) in a browser.

This document explains the project's two load-bearing systems — the **tiered, one-seam test
architecture** and the **docs-as-infrastructure organisation** — and then evaluates them honestly:
what they buy a human, what they buy an AI agent, where they will crack under scale, and what should
be built next.

---

## 1. The numbers (verified 2026-07-10)

| Metric | Value |
|---|---|
| Automated checks in the merge gate | **583** — 346 JUnit tests + 152 Gherkin scenarios + 85 JS tests |
| Tier 0 / Tier 1 / Tier 2 tests | 156 / 136 / 54 (21 / 17 / 9 classes) |
| Acceptance spec | 44 `.feature` files, 152 scenarios (157 after outline expansion), 32 step-definition files |
| Mocking libraries (MockK, Mockito, …) | **0** — not even as a dependency |
| Test : production code | 9,663 : 7,803 LOC (**1.24 : 1** — more test code than product code) |
| Production source | 91 Kotlin files, 35 JTE templates, 19 Flyway migrations, 11 repositories |
| Semantic UI contract | 157 distinct `data-*` attributes in templates (what acceptance asserts instead of CSS) |
| Reverts in the entire git history | 0 |

---

## 2. The testing approach

### 2.1 The spec came first — literally

The repo's first *feature* commit (day 2, `dcde588`) was the acceptance layer itself: 13 `.feature`
files, the step-definition scaffold, `DatabaseResetHooks`, and the Tier-0 tests — before most of the
production code existed. The suite is not a safety net added to a product; the product was built
*behind* the suite. Build order is outside-in (acceptance against mockups first, then views, then
domain/persistence behind the frozen contract), which is what lets sessions implement without
renegotiating the spec.

The `.feature` files are written as genuine specification, not test scripts: declarative,
DOM-agnostic, carrying rationale prose and citations into the requirements doc (`§4`, `§7/§13`,
`T1.4`). A newcomer — human or agent — learns what the product does by reading
[`src/test/resources/features/`](../src/test/resources/features/), not by reverse-engineering
controllers.

### 2.2 The tier ladder

Four Kotlin tiers plus a JS rung, each a Gradle task, ordered lowest-first (advisory
`shouldRunAfter`) so **the lowest failing tier names the culprit** — you never start debugging from a
Cucumber stack trace when a millisecond Tier-0 test already failed on the same logic.

| Tier | What it proves | Real | Stand-ins | Checks |
|---|---|---|---|---|
| **Tier 0** | Pure logic: state machine, context firewall, parsers, markdown, routing traits | everything | none | 156 |
| **Tier 1** | The IO boundary itself: repositories vs **real SQLite**, `claude -p` adapter vs a real `/bin/sh` subprocess, HTTP adapter vs a real local socket, Flyway pipeline, backup | real DB, real subprocesses, real sockets | controlled sub-seam substitutes only (a shell script, `MockRestServiceServer`) | 136 |
| **Tier 2** | Services/controllers as plain objects (no Spring): orchestration, fan-out, cancel, error taxonomy | real service + domain logic | hand-rolled scriptable `LlmClient`, in-memory repo fakes, fixed `Clock` | 54 |
| **Acceptance** | Full stack over HTTP: one `@SpringBootTest(RANDOM_PORT)` context, real JTE SSR, real SQLite + Flyway | everything except IO ports | the four scriptable seam fakes + fixed Clock | 152 scenarios |
| **jsTest** | Pure JS cores (`*-core.mjs`: toast store, nav, quote scanner) via `node:test` | — | injected `now` | 85 |

One command runs the ladder: `./gradlew verifyAll` (also wired into `check`; the default `test` task
is disabled so there is no untiered side door). CI is deliberately a thin ~25-line workflow whose
only build step is `docker compose run --rm build` — **the same command a developer runs locally**,
so "passes locally, fails in CI" is structurally impossible for the Docker path.

Honest caveats, verified: tier ordering is advisory (`shouldRunAfter`), not a dependency chain; the
merge gate is *convention* — there is currently no GitHub branch protection enforcing a green run;
local `jsTest` shells out to `npm test` and silently requires Node ≥ 18; and the `mcp/` servers'
tests sit outside every gate (see §6).

### 2.3 The seam doctrine — mock only at the IO boundary

The suite's honesty guarantee: **there are no mocking libraries in this repo.** Every test double is
plain, readable Kotlin, and doubles are allowed only at constructor-injected IO ports. In the `test`
profile, `@Primary @Profile("test")` beans stand in at four ports —

- `ScriptableLlmClient` (the LLM — scripted per scenario from Gherkin: *"Given the LLM will fail with
  a timeout"*, with a `received` spy so scenarios can assert what the model was **not** shown)
- `ScriptableImageDescriber` (vision captions)
- `ScriptableShortcutClient` (Shortcut API)
- `ScriptableGitHubClient` (`gh` CLI)

— plus a fixed `Clock` and `FailingCommentRepository`, a one-shot fault-injection wrapper that
otherwise delegates to the **real** repository. Everything above the ports runs real code; a green
higher-tier test means the wired-together system actually composes.

Two precision notes the folklore version gets wrong (both grep-verified):

1. It is not literally "one seam" — it is **one *class* of seam** (IO ports), currently four
   scriptable fakes. The pattern has been extended three times (vision, Shortcut, GitHub) without
   breaking the doctrine: each new integration lands behind its own port with a scriptable fake,
   following the `LlmClient` template.
2. Tier 2 does **not** touch SQLite — its repo fakes are in-memory subclasses of the real repository
   classes. That is consistent with the doctrine (the DB *is* IO, so the repo is a port), but "real
   SQLite in every tier" would be false; it's real in Tier 1 and acceptance. There is also exactly
   one double sitting *above* the seam (`SpyGeneration` in one Tier-2 test) — a known judgment call,
   not a licence.

Why so strict? Because a suite that mocks internally can stay green while the system is broken, and
in an agent-driven repo the suite is the primary control layer against drift. No mock DSL also means
an agent can't auto-mock its way past a design problem: needing a second seam is "a design smell to
discuss, not to push through."

### 2.4 Error scenarios, logging, and the UI contract

- **Failure is first-class spec.** Every generation failure mode — timeout, process error,
  rate-limit, empty, malformed, cancel, partial-roomful, couldn't-save — has explicit scenarios,
  driven by scripting the seam fake, asserting both the state transition and the user-visible
  outcome + working retry.
- **Logging is IO.** Operational log lines carry structured `event` ids (`gh.unavailable`,
  `llm.timeout`) and are asserted (level + id + fields) via `LogCapture` at the SLF4J layer — a log
  line is a tested contract, so tooling can be built on it. Silence is asserted too.
- **`data-*` semantic hooks.** Acceptance asserts only stable `data-*` attributes (157 of them),
  never CSS classes. This paid off measurably: the error-toast UX went through **three full
  redesigns under a fully green suite** (fragment swap → HX-Trigger → toast-only + TTL). JTE
  templates are precompiled and typed against DTOs, so a template/DTO mismatch is a *compile*
  failure — a classic agent hallucination converted into an unmissable build break.
- **Red-first machinery.** Scenarios can be committed ahead of implementation tagged `@wip`
  (filtered by `cucumber.filter.tags=not @wip`), and discovery mode (`-Pdiscovery=true` /
  `DISCOVERY_MODE=true`) flips `ignoreFailures` so a sea of red doesn't abort a scaffolding build.
  Note the honest limits: discovery mode lets failures pass — there is **no** "fail if a @wip
  scenario passes" mechanism (folklore says otherwise) — and today zero `@wip` tags remain; the
  spec is fully green.

### 2.5 Has it actually worked?

Evidence, not vibes:

- A **full adversarial audit** (2026-06-25) after a week of multi-agent, PR-per-day cadence found
  exactly **two Low-severity test findings** — one untested adapter, one flaky wall-clock poll —
  both fixed within a day. The tiered architecture itself was graded a strength, "recorded so it
  isn't re-litigated."
- **Zero reverts** in the entire history, across all branches.
- The seam doubles compounded: the day-2 `FailingCommentRepository` powered the transaction-rollback
  fix four days later; `Given the LLM will fail` was reused wholesale for the unrelated toast-UX
  feature.
- Pain got institutionalised, not repeated: the V7 Flyway checksum split was fixed with a test
  extended in the same commit, then codified into the `sqlite-spring-jdbc` skill; the one flaky
  poll became the skill's "de-flake via a test-double latch" section.

---

## 3. The organisation approach

### 3.1 Four documentation layers, each with a job

| Layer | Role | Lifetime |
|---|---|---|
| [`plan_docs/`](../plan_docs/) (20 files) | **Decide.** One doc per feature: status header, data model with the exact Flyway DDL, dated & locked "Decisions (owner)" blocks, slices, deferred items with promotion triggers, a tiered test plan. Rejected approaches are kept *with the why*. | Durable |
| [`.claude/skills/`](../.claude/skills/) (4 skills) | **Encode how-to.** Wiring traps and conventions an agent can't derive from code (Spring Boot 4 removed TestRestTemplate; the Cucumber engine needs a classpath selector under Gradle). Auto-trigger on the relevant paths. Maintained as a release artifact — updating them was a numbered audit task with its own PR. | Durable, must track code |
| [`src/test/resources/features/`](../src/test/resources/features/) | **Specify.** The executable spec — the only layer that can't silently lie, because it runs. | Durable, self-enforcing |
| Session memory + [`HANDOVER.md`](../HANDOVER.md) | **Coordinate.** Cross-session state (maintainer's `~/.claude` memory) and agent-to-agent negotiation notes (HANDOVER.md brokered an `app.js` conflict between two live parallel branches). | Ephemeral by design |

There is deliberately **no CLAUDE.md, no issue tracker, and no coverage tooling**: README + skills +
memory fill the first role, plan-doc status headers + the deferred-audit file (every deferral carries
a concrete promotion trigger) fill the second, and "every behaviour gets a test at the lowest tier
that proves it" substitutes for the third.

### 3.2 The delivery loop

```
plan doc (decisions locked) ──► slices ──► worktree branch per agent session (claude/*)
      ▲                                          │
      │                                          ▼
skills re-synced ◄── merge ◄── PR (persona-signed review: 🛠️ Forge implements, ⚖️ Assay reviews)
```

- **Slices sized to one PR**, at high cadence (PRs #77–#90 in ~3 days at peak).
- **Parallel agents coordinate through documents**: migration numbers are pre-claimed in plan docs
  (the V18/V19 collision between two live branches was avoided by convention), and HANDOVER.md is a
  written negotiation between sessions.
- **The audit as a work order**: `audit-remediation-tier1-tier2.md` encodes a supervisor/worker
  protocol — decision gates, per-task definition-of-done — and git history shows it executed to the
  letter: 11 `claude/audit-t*` branches, PRs #77–#87, skills-update last as ordered.
- **Caveat**: the persona review roles have real process force but fictional provenance — everything
  is one human plus agents under the single `@hevi-public` identity. It is a discipline aid, not
  independent review.

---

## 4. Pros & cons

### For a human

**Pros**

1. **Failures self-localise.** The tier that fails names the layer that broke; debugging starts at a
   millisecond test, not a full-stack trace.
2. **No mock DSL to learn.** Every double is ordinary Kotlin you can step through; failure injection
   reads as a Gherkin one-liner.
3. **The spec is readable.** 44 feature files teach the product; plan docs preserve decisions *and
   rejected paths* with dates — rare solo-repo gold for a joining teammate.
4. **Redesigns are cheap.** The `data-*` contract means restyling can't break 152 scenarios — proven
   three times in one review cycle.
5. **CI == local.** One Docker command, no matrix, no cache config to understand.

**Cons**

1. **Doctrine docs drift.** The skills say "exactly one seam" while reality is four; the two testing
   skills disagree on the acceptance engine; sketches diverge from code. A human copying a skill
   snippet verbatim writes wrong wiring (see §6, item 7).
2. **Critical context lives outside the repo** in the maintainer's private session memory — the
   clearest human-convenience-for-AI-convenience trade. A second human can't read it.
3. **A green build can lie in three places**: acceptance passes even if it discovers zero scenarios
   (`failOnNoDiscoveredTests = false`), `jsTest` needs an undeclared Node ≥ 18, and `mcp/` tests run
   in no gate at all.
4. **Untyped step glue.** Acceptance steps POST raw `Map`s (so specs compile before controllers
   exist) — great for red-first agents, but it costs IDE navigation and rename safety across 32
   step files.
5. **History archaeology is confusing**: persona-signed reviews under one identity, and a stale
   HANDOVER.md at maximum visibility.

### For an AI agent

**Pros**

1. **Ground truth displaces hallucinated requirements.** The executable spec (plus the seam spy: "the
   model's context contained no vote signal") specifies even *invisible* contracts an agent could
   never recover from the UI.
2. **A cheap verification gradient.** Milliseconds (tier 0) → seconds (tier 1/2) → one Spring context
   (acceptance) matches an agent's iterate-verify loop; the deterministic LLM double makes an LLM app
   hermetically verifiable at all — no keys, no quota, no model flakiness.
3. **Skills are retrieval-free context** for exactly the knowledge that isn't derivable from code,
   auto-loaded at the right moment.
4. **Plan docs are cross-session memory and a coordination protocol** — a fresh session resumes
   mid-feature from the status header; parallel sessions pre-claim resources in writing.
5. **Shortcut behaviour is structurally blocked**: no mock library to reach for, and JTE
   precompilation turns view-layer hallucinations into compile errors before any test runs.

**Cons**

1. **Convention volume is a context-window tax**: touching acceptance plausibly means two overlapping
   ~350-line skills + the feature file + the plan doc + the requirements section it cites.
2. **Skill drift produces confidently-wrong agents** — an agent trusting a stale snippet reproduces
   the deprecated pattern with high confidence; drift compounds because the next agent copies it.
3. **Tier discipline is honor-system.** Tier membership is a `@Tag` the author picks; tolerated
   exceptions (`ContextLoadsTest` — a `@SpringBootTest` tagged tier2; `Clock.systemUTC()` inside
   tier-2 fakes) are precedents agents will pattern-match on.
4. **The silent-pass holes are agent-widenable**: a session that breaks the Cucumber suite selector
   greens the build with all 152 scenarios unrun, and nothing red tells it so.
5. **Duplicated FK-safe wipe lists** (`DatabaseResetHooks` wipes 11 tables; tier-1 classes curate
   their own shorter lists) are a per-migration landmine — it has already caused one cross-class
   leak flake (`68a0748`).

---

## 5. What scales, what breaks

**Scales well:** the tier structure itself (growth lands where it's cheap — tiers 0+2 grew to 210
tests with zero slow-suite pressure, and the pressure valve "push enumeration down to Tier 2" is
pre-written); the seam pattern (three new integrations landed on the paved road); the `data-*` +
typed-template contract; and the plan-doc protocol, which is what already made 11-branch parallel
agent work possible.

**Watch list** (severity now → trigger):

| Risk | Now | Trigger | Cheapest counter |
|---|---|---|---|
| Acceptance wall-clock (serial by design: one context, per-scenario 11-table wipe, settle polling) | Medium | scenario growth; docs' own ceiling: "fine at ~63… becomes the thing nobody runs past a few hundred" — 157 executed today | enforce "one journey per feature, enumeration at Tier 0/2"; surface scenario count + runtime per PR |
| Silent-zero acceptance pass (`failOnNoDiscoveredTests = false`) | Medium | any Gradle/Cucumber/JUnit bump or runner refactor | delete the flag or assert ≥N scenarios in `report.json` — one line |
| Skill/doc drift | Medium | more parallel sessions; drift compounds by imitation | keep T2.7 ("skills as release artifact") as a standing PR checkbox; make skills point at real files instead of embedding sketches; delete stale HANDOVER.md |
| Duplicated DB-wipe lists | Medium | every new migration (V20+) | one shared `wipeAll()` + a guard test diffing the list against `sqlite_master` |
| Flyway numbering under concurrent agents | Low–Med | 3+ concurrent branches needing migrations | a duplicate-V-number build check; keep the plan-doc reservation convention |
| Worktree/Gradle cache contamination (stale JTE served from shared `~/.gradle`) | Low–Med | more concurrent builds | encode `--no-build-cache` for JTE generation in build.gradle.kts instead of in one person's memory |
| SQLite single-writer + un-parallelisable acceptance DB | Low (correct PoC posture) | multi-user deployment | none now; the Tier-1 repo suite over real SQLite *is* the pre-built Postgres migration harness |

---

## 6. What else is needed (ranked)

Accepted investments — each is either a hole in what *green means*, a prose rule that should become
mechanical, or the missing feedback loop for real users. Nothing here adds a new tier or a second
seam class.

1. **Markdown link-URL sanitization + tests (S).** ✅ **Done — PR #92** (2026-07-10, same day it was
   found). The HTML-escaping half of the renderer's XSS firewall was tested; the link-destination
   half wasn't sanitized. Fixed with `sanitizeUrls` + an `http/https/mailto` allowlist, pinned by
   Tier-0 hostile-scheme cases and an acceptance scenario — see `plan_docs/markdown-rendering.md`
   §Security for the convention.
2. **Close the silent-green holes (S).** Drop `failOnNoDiscoveredTests = false` (or assert a minimum
   scenario count) and wire `mcp/`'s `npm run test:mcp` into `verifyAll` beside `jsTest`.
3. **Centralise DB reset/seeding in `testsupport/` (S).** One canonical FK-safe table registry +
   `wipeAll()` used by hooks and every Tier-1 class, plus a guard test against `sqlite_master`.
4. **Opt-in LLM provider contract task (M).** The one thing the hermetic suite cannot prove is that
   `claude -p` / the OpenAI endpoint still emit what the parsers assume — and envelope drift is a
   *proven* incident class here (reasoning-leak, think-token wrinkle). A non-gating `providerContract`
   task runs the real adapters and refreshes the Tier-0 canned envelopes.
5. **Konsist architecture tests at Tier 0 (S–M).** Mechanise the prose rules: no `Instant.now()`
   mid-stack, `domain/` imports nothing from Spring, tier-2 classes are `@SpringBootTest`-free,
   doubles implement IO ports only. These are plain JUnit — they slot into the existing tier model.
6. **A prod-error surface (S–M).** Structured event-id logging is built and tested but nothing
   consumes it. Persist ERROR events to SQLite and render on `/admin/stats` — the first consumer,
   needed the day the first outside user hits a bug the owner didn't witness.
7. **Recurring docs-drift audit (S).** Five live drifts exist despite T2.7. Prose-vs-code diffing is
   an LLM job and this repo's workforce is LLMs: rerun the audit-work-order format on the skills
   quarterly.
8. **Scoped mutation testing (M).** PIT on tiers 0+2 only (plain junit-jupiter — the recorded
   PIT/Cucumber blocker doesn't apply), on-demand, as the gate proving an assertion migrated safely
   out of acceptance.

**Considered and rejected** (with the trigger that would flip them): coverage measurement (first
external contributor → diff-coverage only), performance/load rig (multi-user deploy or observed
`busy_timeout` contention), accessibility automation (when a Playwright layer exists anyway), visual
regression (permanently — it opposes the `data-*` redesign-survival contract), flaky-test quarantine
(permanently — fix flakes by design, as T2.4 did), dependency-update automation (honour the T3.5
deferral; do flip on free security-only alerts).

---

## 7. Provenance

Produced 2026-07-10 by a 12-agent research workflow: four parallel mappers (architecture, census,
organisation, history), four critique lenses (human, AI, scale/risk, gaps), three adversarial
verifiers (seam claim, gate claims, numbers), and a completeness critic. Two of fifteen headline
numbers failed independent re-derivation and were corrected (tier-1 test count 137→136; test LOC
9,643→9,663); three folklore claims were qualified or refuted before publication ("one seam",
"blocks a merge", "fails if @wip passes"). Treat this file like the skills: **it drifts** — re-run
the numbers before quoting them in six months.
