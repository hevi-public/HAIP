# AI Forum (HAIP)

An owner-driven brainstorming forum where hand-authored AI personas reply in a nested comment tree.
The differentiator is **per-branch context scoping** — each generation is given exactly the slice of
the thread you choose (just the ancestor path, or the whole tree).

This repository currently contains the **acceptance-test layer, built test-first** (BDD/TDD): the
executable specification and a walking skeleton, so feature implementation happens behind a frozen,
test-enforced contract. Source spec lives in [`plan_docs/`](plan_docs/); design mockups in
[`HAIP_design/`](HAIP_design/).

## Stack

| | |
|---|---|
| Language / runtime | Kotlin 2.4.0, Java 21 |
| Framework | Spring Boot 4.1 (Spring Framework 7), SSR via JTE 3.2.4, API-first DTOs |
| Persistence | SQLite + `spring-jdbc` (`JdbcTemplate`, recursive CTEs) + Flyway 12.4 (`flyway-database-nc-sqlite`) — **not** Hibernate |
| LLM | `claude -p` behind an injected `LlmClient` seam (mocked under the `test` profile) |
| Tests | Cucumber-JVM 7.34 over HTTP (`@SpringBootTest(RANDOM_PORT)` + `RestClient`), JUnit 6 |
| Build / CI | Gradle 9.5.0 (Kotlin DSL); Dockerized build entrypoint + thin GitHub Actions wrapper |

## Quick start

The Dockerized build is the source of truth and runs the whole pipeline identically locally and in CI:

```bash
docker compose run --rm build
```

This runs, in order: `generateJte → compileKotlin → tier0 → tier1 → tier2 → acceptance`.
A Cucumber HTML/JSON report lands in `build/reports/cucumber/`.

### Running with Gradle directly

Requires **JDK 21**. If you use jenv (project default is 21) `./gradlew` just works; otherwise point
it at a JDK 21:

```bash
./gradlew verifyAll          # all tiers, lowest-first
./gradlew tier0              # pure-function tests only
./gradlew acceptance         # Cucumber acceptance suite
```

Run the app itself (the `dev` profile, real `claude -p` generation) and drive the htmx UI in a
browser at **http://localhost:8080**:

```bash
./gradlew bootRun
```

The port is Spring's default; nothing pins it, so a Docker deployment remaps the host side
(`-p 9000:8080`) or sets `SERVER_PORT` without a code change. The `data/` directory is created
automatically on startup, so a fresh checkout boots without any manual setup.

On first launch the app seeds a small default team of personas — **Sol** (backend), **Saul**
(frontend), **Paul** (QA), **Mira** (PM), **Dana** (design) — so the forum is usable immediately;
edit the roster under `aiforum.seed.personas` in `application.yml`, or manage personas at `/personas`.
Seeding is idempotent (skips any that already exist) and disabled under the `test` profile.

**Discovery mode** — let a sea of red run without failing the build (useful while scaffolding):

```bash
DISCOVERY_MODE=true docker compose run --rm build
./gradlew verifyAll -Pdiscovery=true
```

## Test architecture (tiered)

Mocking happens at exactly **one** seam — the IO boundary (`LlmClient`, `Clock`, repositories), all
constructor-injected. Everything above it runs real code against that single fake.

| Tier | What | Mocks |
|------|------|-------|
| **Tier 0** | Pure functions (`GenerationStateMachine`, `ContextAssembler` firewall) | none |
| **Tier 1** | The IO boundary itself (repositories vs real test SQLite) | the seam |
| **Tier 2** | Controllers / services on the single Tier-1 fake | `LlmClient` fake |
| **Acceptance** | Full stack over HTTP, real test SQLite | `LlmClient` fake only |

Profiles are isolated (`prod` / `dev` / `test`); under `test` the app uses a separate DB and disables
backups — and those guardrails are themselves asserted by a rail scenario.

## Acceptance coverage

Every `.feature` scenario now passes under the suite's `not @wip` filter — the executable spec is
fully green, with nothing left tagged `@wip`:

- Generation lifecycle (summon → posted)
- All sad paths + working retry — timeout, process error, empty, malformed, rate-limit
- The `+1` firewall — owner votes are recorded and shown, never reach the model (spy-asserted)
- Composer validation (rejected before any LLM call)
- Composer reply targeting + htmx submit — inline composer targets the clicked node (defaults to branch scope), the bottom composer targets level 0, summon posts over htmx
- Config guardrails (test → test DB, backups off)
- Per-branch context scoping (branch-only vs whole-thread, via recursive CTE)
- Sequential fan-out / partial-roomful (one persona fails, the room still posts)
- Depth-budget autonomy — `/more` grants depth budget, an owner reply re-grants it, autonomous growth stalls when the budget is exhausted
- New-thread creation (owner starts a thread and asks the room)
- Personas & admin (view a persona profile, admin adds a persona)
- Empty-state & unread badges (fresh-forum empty state, thread unread count)

## Project layout

```
src/main/kotlin/com/aiforum/
  llm/        LlmClient seam (interface, ProcessLlmClient wrapping real `claude -p`, PromptContext, exceptions)
  domain/     Comment, lifecycle/GenerationStateMachine, context/ContextAssembler (Tier 0)
  dto/        ReplyView + enums (the frozen view-contract)
  repo/       JdbcTemplate repositories (recursive CTEs)
  service/    GenerationService (orchestration)
  web/        controllers (generation, owner controls, diagnostics)
  config/     ClockConfig, ProfileGuard, DataDirectoryInitializer (auto-creates the SQLite data dir
              at startup), PersonaSeeder + PersonaSeedProperties (seeds the default persona team)
src/main/jte/ layout.kte (page shell + htmx) · fragments/ (composer, replyNode, replyList) — stable data-* hooks
src/main/resources/static/ app.css + app.js — hand-written styling layer (sage HUP aesthetic, htmx-aware auto-grow)
src/test/kotlin/com/aiforum/
  acceptance/ Cucumber↔Spring wiring, scriptable LlmClient fake, steps, hooks, support
  tier0/      pure-function tests
src/test/resources/features/  *.feature (the spec)
```

## Working in this repo with Claude Code

Project-scoped skills in [`.claude/skills/`](.claude/skills/) encode the trickier wiring and are
auto-loaded in new sessions: `jte-spring-kotlin`, `cucumber-spring-bdd`, `sqlite-spring-jdbc`,
`bdd-tiered-testing`.

[`.claude/launch.json`](.claude/launch.json) lets Claude Code's preview/verify harness boot the app
(`./gradlew bootRun`, port 8080) to exercise the htmx composer in a real browser — the one layer the
HTTP acceptance suite can't drive. It's committed on purpose so browser verifies are reproducible.
