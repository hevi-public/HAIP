---
name: bdd-tiered-testing
description: The AI Forum project's BDD/TDD testing philosophy and tiered test architecture. Use this whenever writing or organizing tests in this Kotlin/Spring Boot codebase — deciding what to mock, where a test belongs (Tier 0/1/2/acceptance), wiring constructor injection for the IO seam, adding Gradle test tasks, setting up the discovery-mode build gate, or covering generation error scenarios. Consult it before adding any new test so the suite stays a trustworthy executable spec and the "mock only at one seam" guarantee holds.
---

# BDD/TDD tiered testing for AI Forum

This skill encodes *how* we test (requirements §14). The suite is the primary control layer
against agent drift and the executable spec the implementing team builds behind — so it must stay
honest. Honesty here means two things: a test that fails means real behaviour broke (not a brittle
mock), and the place a test lives tells you what it actually exercises.

## The one load-bearing rule: mock only at Tier 1

There is exactly **one** seam where we substitute fakes: the IO boundary. Everything above it runs
**real** code against that single fake. If you find yourself mocking a service to test a controller,
or stubbing an internal method, stop — that hides the very integration the test exists to prove.

Why this matters: a suite that mocks internally can stay green while the wired-together system is
broken. By allowing fakes at only the IO edge, a green higher-tier test means the real domain +
controller + view actually compose correctly.

## The tiers

| Tier | What it tests | Mocks | Example |
|------|---------------|-------|---------|
| **Tier 0** | Pure functions / logic, no side effects | none | `DepthBudget.isExhausted()`, `ContextAssembler` firewalling `+1`, `GenerationStateMachine` transitions |
| **Tier 1** | The IO boundary itself | nothing above it; this *is* the seam | `JdbcCommentRepository` against a real test SQLite DB; the `LlmClient` fake's own behaviour |
| **Tier 2+** | Controllers / domain orchestration | the single Tier-1 fake (`LlmClient`, `Clock`, a repo fake) | `GenerationService` running real Tier-0 logic, calling the scripted `LlmClient` |
| **Acceptance / E2E** | Full stack over HTTP | only the `LlmClient` fake (DB is real test SQLite) | Cucumber scenarios via `TestRestTemplate` |

Run order is **lowest-first** (Tier 0 → 1 → 2 → acceptance). A break low down ripples upward, so the
lowest failing tier names the culprit — read it first and ignore the cascade above it.

Build order is the **opposite** (outside-in, top-down): write acceptance tests against the mockups
first (RED), bring the view layer up on mocked data (GREEN at the view-contract level), then fill in
domain + persistence last behind the now-frozen contract. Writing tests first pins the contract
before any logic exists, which is what lets the team implement without breaking the spec.

## Constructor injection is the discipline that keeps the seam intact

The "one mock level" guarantee only holds if the boundary is *injectable*. So every dependency that
touches the outside world — `LlmClient`, `Clock`, repositories — is passed by **constructor
injection**, never reached for internally. The failure mode is gradual: an agent adds an
`Instant.now()` here, a `new ProcessBuilder()` there, and suddenly a tier can't be tested in
isolation. Hold the line:

```kotlin
// GOOD — the seam is injected, so tests substitute it
class GenerationService(
    private val llm: LlmClient,
    private val clock: Clock,
    private val replies: ReplyRepository,
)

// BAD — un-mockable; reaches for the world internally
class GenerationService {
    fun generate() {
        val now = Instant.now()                 // ← static call, can't fix the clock
        val out = ProcessBuilder("claude", "-p")  // ← un-injectable IO
    }
}
```

If you see `Instant.now()`, `Math.random()`, `new`/direct construction of an IO type, or a static
file/network read mid-stack, that's a yellow flag — route it through an injected dependency.

## Error scenarios are first-class

Every failure mode in the generation lifecycle (§4) gets explicit coverage: timeout, process error,
auth/rate-limit, empty output, truncated/malformed, cancel, partial-roomful, persistence failure,
validation, context-overflow. These are exactly what the single Tier-1 seam exists to simulate —
inject a fake that throws or returns the failure, then assert the **state transition**
(drafting → failed(reason) → retry → posted) and the **user-visible outcome + working retry**.

Validation is asserted at the controller tier (no LLM call should happen — assert the fake's spy
received nothing). Cancel exercises the subprocess-kill path via a `CancellationToken`.

## Build-breaks-on-red, with an opt-in discovery mode

Default stance: a failing test fails the build. That's what makes the suite a control layer rather
than a suggestion. But while scaffolding it's useful to run a sea of red without the build aborting,
so a **discovery mode** flips `ignoreFailures` on:

```kotlin
val discoveryMode = (project.findProperty("discovery") == "true") ||
                    (System.getenv("DISCOVERY_MODE") == "true")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    ignoreFailures = discoveryMode    // default false → red breaks the build
}
```

Toggle with `./gradlew test -Pdiscovery=true` or `DISCOVERY_MODE=true` (the env var is how the
Docker entrypoint passes it through).

## Tagged Gradle test tasks (tiered run)

Tag JUnit tests with `@Tag("tier0")` etc.; give Cucumber its own task. Order them so the lowest tier
runs first:

```kotlin
// GOTCHA: a manually-registered Test task does NOT inherit the test source set's classes/classpath
// the way the built-in `test` task does — without this it reports "NO-SOURCE" and runs nothing.
val testSrc = sourceSets.test.get()
fun Test.tier(tag: String) {
    testClassesDirs = testSrc.output.classesDirs
    classpath = testSrc.runtimeClasspath
    useJUnitPlatform { includeTags(tag) }
    ignoreFailures = discoveryMode
}

tasks.register<Test>("tier0")      { tier("tier0") }
tasks.register<Test>("tier1")      { tier("tier1"); shouldRunAfter("tier0") }
tasks.register<Test>("tier2")      { tier("tier2"); shouldRunAfter("tier1") }
tasks.register<Test>("acceptance") {
    testClassesDirs = testSrc.output.classesDirs
    classpath = testSrc.runtimeClasspath
    useJUnitPlatform { includeEngines("cucumber") }
    systemProperty("cucumber.filter.tags", "not @wip")
    // Before any .feature files exist, an engine filter that matches nothing trips JUnit's
    // "no tests discovered" failure (tag-filtered tier tasks are exempt). Relax it while scaffolding;
    // once features exist they run and failures show as red, which is the point.
    failOnNoDiscoveredTests = false
    shouldRunAfter("tier2")
}
tasks.register("verifyAll") { dependsOn("tier0", "tier1", "tier2", "acceptance") }
```

## Profile isolation is itself tested

The `test` profile must use the test DB, disable backups, and never see a prod datasource. These
guardrails are config and config silently drifts — so they get explicit *rail* scenarios that assert
the wiring (active datasource URL points at the test DB, backups off, no prod datasource bean). See
[[sqlite-spring-jdbc]] for the datasource/profile setup and [[cucumber-spring-bdd]] for the rail
scenario shape.

## Tests double as documentation

A method's behaviour is defined by its tests, so write them to read as behavioural descriptions:
clear names, arrange-act-assert. A future reader (human or agent) consults the test to learn what the
method does — make it worth reading.

## Where to go next

- Acceptance/Cucumber wiring, the `LlmClient` fake, scenario scope → [[cucumber-spring-bdd]]
- Real test SQLite, recursive CTEs, profiles → [[sqlite-spring-jdbc]]
- JTE view layer the acceptance tests assert against → [[jte-spring-kotlin]]
