# Docs-drift audit — recurring work order

Status: **standing work order** (first run 2026-07-10, five drifts found & fixed). Re-run quarterly, or
after any refactor that touches the build wiring, test doctrine, or an external-facing dependency.
Prose-vs-code diffing is an LLM job and this repo's workforce is LLMs — one session executes the whole
order in under an hour.

## Why this exists

The skills under `.claude/skills/` are doctrine: agents execute their snippets with high confidence, so
a stale snippet doesn't just misinform — it **reproduces the deprecated pattern**, and drift compounds
because the next agent copies it (see `how-we-work/README.md` §4/§5). T2.7 made skills a release
artifact; this order is the enforcement loop.

## The work order

For each doc in scope — the four skills (`.claude/skills/*/SKILL.md`), `how-we-work/README.md`,
`how-we-work/infographic.html`, `how-we-work/context.md`, `CLAUDE.md` — do:

1. **Extract checkable claims.** Anything falsifiable against the tree: engine/runner names, Gradle task
   names and wiring, artifact coordinates and versions, class/interface names, the seam/port inventory,
   file paths, tier definitions, gate composition, tag conventions, counted numbers ("N scenarios").
2. **Verify each claim against reality**, not against another doc (two docs can share a drift). Reality
   is: `build.gradle.kts`, `settings.gradle.kts`, `package.json`(s), `Dockerfile`/`docker-compose.yml`,
   `src/main` + `src/test` source, `junit-platform.properties`.
3. **Check the sketches.** Any embedded code block gets diffed semantically against the real file it
   sketches. Where a sketch exists, ensure it carries a "source of truth is `<file>`" pointer.
4. **Check cross-doc agreement last** (skills citing each other, README vs infographic wording): after
   each doc agrees with the code, disagreements between docs surface mechanically.
5. **Output a drift table** — `| doc:line | claim | reality | severity |` — then either fix in the same
   session or file the table in a PR description. Update this doc's log below.

Fan-out shape for a multi-agent run: one Explore agent per doc extracting claims, one verifying agent
per claim cluster (build wiring / seams / HTTP layer / versions), a final agent for step 4.

## Run log

### 2026-07-10 — first run (found during the how-we-work audit, fixed same day)

| Where | Claim | Reality |
|-------|-------|---------|
| bdd-tiered-testing (7 spots) | "exactly one seam" / acceptance mocks "only the `LlmClient` fake" | four scriptable IO ports (`LlmClient`, `ImageDescriber`, `ShortcutClient`, `GitHubClient`) in `TestBeans.kt` |
| bdd-tiered-testing Gradle sketch | acceptance = `includeEngines("cucumber")` + `systemProperty` tag filter | `junit-platform-suite` engine + tag filter in `junit-platform.properties` (the sketch's wiring discovers zero features under Gradle) |
| bdd-tiered-testing + cucumber-spring-bdd (4 spots) | `TestRestTemplate` drives acceptance HTTP | Spring Boot 4 removed it; `acceptance/support/HttpClient.kt` over `RestClient` (cucumber-spring-bdd even said both — self-contradiction) |
| jte-spring-kotlin (2 spots) | `jte-spring-boot-starter-3` | `jte-spring-boot-starter-4` (build.gradle.kts) |
| bdd-tiered-testing | production adapter is `ProcessLlmClient` (sole) | `OpenAiLlmClient` + `OpenAiImageDescriber` also ship; `verifyAll` sketch also omitted `jsTest` |
