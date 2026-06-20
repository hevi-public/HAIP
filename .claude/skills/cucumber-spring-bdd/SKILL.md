---
name: cucumber-spring-bdd
description: Wiring Cucumber-JVM HTTP-level acceptance tests into a Spring Boot + Kotlin app for the AI Forum project. Use this whenever adding or fixing acceptance tests — the JUnit Platform Suite runner, the single @CucumberContextConfiguration + @SpringBootTest(RANDOM_PORT) class, step-definition layout and Spring bean injection, per-scenario state via @ScenarioScope, @Before/@After reset hooks, custom @ParameterType, TestRestTemplate usage, or programming the LlmClient test double. Reach for it before touching anything under src/test/.../acceptance or any .feature wiring, so the suite boots one context and scenarios stay isolated.
---

# Cucumber-JVM + Spring Boot (HTTP-level) for AI Forum

Acceptance tests drive the app over HTTP — `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`, no
browser, no DOM. Step definitions speak HTTP and assert on status, response bodies/DTOs, and stable
`data-*` semantic hooks in rendered HTML. Keeping Gherkin DOM-agnostic means the same `.feature`
files can later be re-pointed at a Playwright step layer for SPA E2E without rewriting scenarios.

This is the acceptance tier of [[bdd-tiered-testing]] — read that for the tiering philosophy; this
skill is the concrete wiring.

## Dependencies (Gradle, test scope)

JUnit and the suite engine come managed by the Spring Boot BOM; pin only Cucumber.

```kotlin
testImplementation("org.springframework.boot:spring-boot-starter-test") // TestRestTemplate, JUnit 6
testImplementation("org.junit.platform:junit-platform-suite")           // version via SB 4.1 BOM
testImplementation("io.cucumber:cucumber-java:7.34.3")
testImplementation("io.cucumber:cucumber-spring:7.34.3")
testImplementation("io.cucumber:cucumber-junit-platform-engine:7.34.3")
```

Spring Boot 4.1 brings JUnit Platform 6 / Jupiter 6; Cucumber 7.34+ supports it. The Cucumber
versions are NOT BOM-managed, so pin them together.

## The runner (JUnit Platform Suite)

One suite class discovers the `.feature` files and points Cucumber at the glue package. Put it at the
test root.

```kotlin
import org.junit.platform.suite.api.*
import io.cucumber.junit.platform.engine.Constants.*

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.aiforum.acceptance")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, html:build/reports/cucumber/report.html, json:build/reports/cucumber/report.json",
)
class RunCucumberTest
```

`src/test/resources/junit-platform.properties` is the alternative home for these params (handy when
multiple suites share config):

```properties
cucumber.glue=com.aiforum.acceptance
cucumber.plugin=pretty, html:build/reports/cucumber/report.html, json:build/reports/cucumber/report.json
cucumber.publish.quiet=true
```

## The Spring context (exactly one such class)

Cucumber needs precisely **one** class annotated `@CucumberContextConfiguration`. Two is a hard
error; zero means no Spring. This is where the app actually boots, on a random port, under `test` —
which activates the `@Primary` `LlmClient` fake and the fixed `Clock`.

```kotlin
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CucumberSpringConfiguration
```

## Feature discovery: a @Suite with @SelectClasspathResource

Run features through a JUnit Platform `@Suite`, not the bare cucumber engine. Under Gradle the engine
only scans compiled-class roots, not the resources dir, so without an explicit selector it finds zero
features. The suite fixes that:

```kotlin
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")   // ← makes .feature discovery reliable under Gradle
class RunCucumberTest
```

Then point the acceptance Gradle task at `includeEngines("junit-platform-suite")` and the tier tasks at
`includeEngines("junit-jupiter")`, so they never run each other's tests.

## Step definitions: live in the glue package, inject by constructor

Step classes go in `com.aiforum.acceptance` (the `cucumber.glue` value). cucumber-spring resolves
their constructor dependencies from the Spring context, so inject what you need.

**Spring Boot 4 removed `TestRestTemplate`** — use the production `RestClient` with `.exchange()`
(which, unlike `.retrieve()`, does not throw on 4xx/5xx, so you can assert on 404s). Read
`local.server.port` from `Environment` at call time, NOT via `@Value` at construction (under
RANDOM_PORT the port isn't set until the server starts, after the bean is built):

```kotlin
class GenerationSteps(
    private val http: HttpClient,            // thin wrapper over RestClient (reads port lazily)
    private val llm: ScriptableLlmClient,    // the @Primary test double
    private val world: ScenarioWorld,        // per-scenario state holder
) {
    @When("the owner summons {string}")
    fun summon(persona: String) {
        world.lastResponse = http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf("personaIds" to listOf(persona), "text" to "?", "triggerMode" to "SUMMON"),
        )
    }
}
```

Post `Map`s (not typed DTOs) from steps so the glue compiles before the controllers/DTOs exist — that
keeps Phase B genuinely RED (404) rather than red-because-it-won't-compile.

**Spring Boot 4 defaults to Jackson 3** (`tools.jackson`, not `com.fasterxml`). For request DTOs to be
idiomatic Kotlin — Kotlin defaults applied to omitted fields, null-safety enforced — add the **Jackson
3** Kotlin module: `implementation("tools.jackson.module:jackson-module-kotlin")` (BOM-managed). The
old `com.fasterxml.jackson.module:jackson-module-kotlin` is the Jackson 2 module and is dead weight
under Spring Boot 4. Without the right module, an omitted non-null `Boolean` field 400s with "Cannot
map null into type boolean" (the symptom that reveals the wrong/missing module).

Inject `TestRestTemplate` directly (it's auto-configured under `@SpringBootTest(RANDOM_PORT)`). Wrap
raw HTTP calls in a small `support/HttpClient.kt` so a future Playwright swap touches one file.

## Per-scenario state: @ScenarioScope, never step fields

The trap is storing scenario state (ids, last response, the programmed fake) as fields on a step
class — the container may reuse instances and state leaks across scenarios, producing flakiness. Put
**all** mutable scenario state in a `@ScenarioScope` bean that's a fresh instance per scenario:

```kotlin
import io.cucumber.spring.ScenarioScope

@Component
@ScenarioScope
class ScenarioWorld {
    var threadId: String? = null
    var lastResponse: ResponseEntity<String>? = null
    val createdReplyIds = mutableListOf<String>()
}
```

Inject `ScenarioWorld` into every step class that needs to share state; because it's scenario-scoped,
each scenario gets its own.

## Reset hooks: ordered @Before

Reset the DB, the `LlmClient` fake, and the clock at the start of each scenario. Order matters — DB
first, then fakes.

```kotlin
class DatabaseResetHooks(
    private val jdbc: JdbcTemplate,
    private val llm: ScriptableLlmClient,
    private val failRepo: FailingRepositoryToggle,
) {
    @Before(order = 0)
    fun resetDb() {
        jdbc.execute("DELETE FROM comment"); /* ... all tables ... */
        // re-apply src/main/resources/db/fixtures/test-fixtures.sql
    }

    @Before(order = 10)
    fun resetFakes() { llm.reset(); failRepo.clear() }

    @After
    fun cleanup() { llm.reset() }
}
```

Using the **real** test SQLite DB here (not an in-memory mock) means every scenario exercises the
actual datasource + recursive-CTE wiring — see [[sqlite-spring-jdbc]].

## The Tier-1 LlmClient seam and its test double

The single IO seam for generation. The production impl wraps `claude -p` via `ProcessBuilder`; under
`test` a `@Primary` scriptable fake stands in. The fake does two jobs: return scripted
output/failures, and **spy** on what it received (used to prove the `+1` firewall — the owner's vote
and identity must be absent from the `PromptContext`).

```kotlin
@Component @Primary @Profile("test")
class ScriptableLlmClient : LlmClient {
    private val script = ArrayDeque<Behavior>()
    val received = mutableListOf<LlmRequest>()       // ← spy for firewall/context assertions

    sealed interface Behavior {
        data class Respond(val text: String) : Behavior
        data class Fail(val ex: () -> LlmException) : Behavior
        data class HangThenCancel(val tripped: () -> Boolean) : Behavior   // cancel scenarios
    }

    fun enqueue(b: Behavior) = script.addLast(b)
    fun reset() { script.clear(); received.clear() }

    override fun generate(req: LlmRequest, cancel: CancellationToken): LlmResponse {
        received += req
        return when (val b = script.removeFirstOrNull() ?: Behavior.Respond("default reply")) {
            is Behavior.Respond -> LlmResponse(b.text)
            is Behavior.Fail -> throw b.ex()
            is Behavior.HangThenCancel -> { while (!cancel.isCancelled) { /* wait */ }; throw LlmException.Cancelled() }
        }
    }
}
```

Persistence failure (category E) is simulated at the IO boundary too — a `FailingRepositoryToggle`
flag that a thin repo wrapper reads to throw on the next write — never by mocking an internal service.
Cancel is simulated with `HangThenCancel` plus a step that POSTs the cancel endpoint to trip the
token; in prod the same token drives `process.destroyForcibly()`.

## Custom @ParameterType for failure modes

Map Gherkin words to `LlmException` factories so the sad-path Scenario Outline reads cleanly:

```kotlin
class ParameterTypes {
    @ParameterType("timeout|process error|empty output|malformed|rate-limit")
    fun failureMode(word: String): () -> LlmException = when (word) {
        "timeout" -> { -> LlmException.Timeout() }
        "process error" -> { -> LlmException.ProcessError(1) }
        "empty output" -> { -> LlmException.EmptyOutput() }
        "malformed" -> { -> LlmException.MalformedOutput("…") }
        "rate-limit" -> { -> LlmException.RateLimited(Duration.ofMinutes(7)) }
        else -> error("unknown failure mode $word")
    }
}
```

## Representative scenario sketches

The `+1` firewall — assert on the spy, not the UI:

```gherkin
Scenario: Owner +1 is recorded but firewalled from the LLM context
  Given a thread "Scaling SQLite" with a reply from "sol"
  And the LLM will respond with "Indexes help here"
  When the owner gives "+1" to sol's reply
  And the owner summons "vex" on that branch
  Then the LLM context handed to the spy must NOT contain any vote signal
  And the LLM context handed to the spy must NOT identify the owner
  And the owner can see the "+1" count is 1 on sol's reply
```

Sad-path Scenario Outline:

```gherkin
Scenario Outline: A generation failure surfaces the right state with a working retry
  Given a thread with a pending summon of "<persona>"
  And the LLM will fail with a <failureMode>
  When the generation runs
  Then the reply node has state "<state>" and failureCategory "<category>"
  And the node is retryable is <retryable>
  When the LLM will respond with "recovered" and the owner taps Retry
  Then the reply node has state "posted"

  Examples:
    | persona | failureMode   | state  | category     | retryable |
    | sol     | timeout       | failed | FAILED_RETRY | true      |
    | sol     | process error | failed | FAILED_RETRY | true      |
    | sol     | empty output  | failed | FAILED_RETRY | true      |
    | sol     | malformed     | failed | FAILED_RETRY | true      |
    | sol     | rate-limit    | failed | RATE_LIMITED | true      |
```

Config-guardrail rail:

```gherkin
Scenario: Under the test profile the app uses the test DB and disables backups
  Then the active datasource URL points at the test database
  And backups are disabled
  And no production datasource bean exists
```

Assert these via a read-only diagnostics endpoint exposed only under `test`, or by injecting the
config beans into the step class.

## Common failure points

- **A glue class annotated `@Component`** → cucumber-spring refuses to start ("marking it as a candidate
  for auto-detection by Spring … may lead to duplicate bean definitions"). Step/hook/@ParameterType
  classes must NOT be `@Component` — Cucumber instantiates them and injects their constructor deps from
  the context. (Plain support beans with no step/hook annotations *should* be `@Component`.)
- **Two `@CucumberContextConfiguration` classes** → "found multiple" error. Keep exactly one.
- **Glue package mismatch** → "undefined steps". The `cucumber.glue` value must contain every step,
  hook, and `@ParameterType` class.
- **State leaking between scenarios** → flaky tests. Move it into `@ScenarioScope` `ScenarioWorld`.
- **`local.server.port` unresolved** → ensure `webEnvironment = RANDOM_PORT` (not `MOCK`).
- **Fake not picked up** → confirm `@Primary @Profile("test")` and that the suite sets
  `@ActiveProfiles("test")`.

## Verify the wiring

`./gradlew acceptance` should boot one Spring context, discover the features, and run them. With no
controllers yet the scenarios fail (RED for the right reason — "undefined" steps are a *wiring* bug,
a connection-refused/404 is the *expected* red). Add `cucumber.execution.dry-run=true` to a properties
file to check glue resolves without booting the app.
