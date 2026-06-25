package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The honest-failure-UX acceptance steps (T1.4). The model failure itself is enqueued by the existing
 * `the LLM will fail with a {failureMode}` step (GenerationSteps) — the single LlmClient seam — so these
 * steps only drive the htmx-vs-not request and assert on the rendered error fragment's stable data-*
 * hook (per the jte-spring-kotlin convention), never on the copy.
 *
 * The surface is the persona prompt-compose PREVIEW (POST /personas/compose): its synchronous LLM call
 * (PromptComposer → llm.generate) is unguarded by design, so an enqueued failure escapes uncaught to
 * HtmxErrorAdvice — exactly the Whitelabel-in-a-fragment-slot bug this task fixes.
 */
class HtmxErrorSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    private val previewForm = mapOf(
        "name" to "ghost",
        "abilities" to "kotlin",
        "dial_agreeableness" to 2,
        "dial_verbosity" to 1,
    )

    @When("the owner previews a persona prompt over htmx and the model fails")
    fun previewOverHtmx() {
        val resp = http.postFormHtmx("/personas/compose", previewForm)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @When("the owner previews a persona prompt without htmx and the model fails")
    fun previewWithoutHtmx() {
        val resp = http.postForm("/personas/compose", previewForm)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the response is the inline error fragment")
    fun responseIsErrorFragment() {
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-error-fragment", "server"),
            "expected the inline error fragment (data-error-fragment=\"server\") in:\n${world.lastBody}",
        )
    }

    @Then("the response is not the inline error fragment")
    fun responseIsNotErrorFragment() {
        assertFalse(
            Html.hasAttr(world.lastBody ?: "", "data-error-fragment", "server"),
            "expected NO inline error fragment in:\n${world.lastBody}",
        )
    }

    @Then("the response is not a whole error page")
    fun responseIsNotWholePage() {
        val body = world.lastBody ?: ""
        // A bare fragment has no document shell; the Whitelabel page (or any full page via layout.kte)
        // does. Asserting the absence of <html>/<!DOCTYPE> proves htmx gets a swap-safe fragment, not a
        // page that would corrupt the target.
        assertFalse(
            body.contains("<html", ignoreCase = true) || body.contains("<!DOCTYPE", ignoreCase = true),
            "expected a bare fragment (no <html>/<!DOCTYPE>), but got a whole page:\n$body",
        )
    }
}
