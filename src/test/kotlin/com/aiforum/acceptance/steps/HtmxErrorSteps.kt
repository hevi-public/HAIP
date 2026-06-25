package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.ResponseEntity

/**
 * The honest-failure-UX acceptance steps (T1.4). The model failure itself is enqueued by the existing
 * `the LLM will fail with a {failureMode}` step (GenerationSteps) — the single LlmClient seam — so these
 * steps only drive the htmx-vs-not request and assert on the corrected contract: a SWAPPABLE error
 * fragment (stable data-error-fragment hook, per the jte-spring-kotlin convention) at HTTP 200, plus the
 * out-of-band failure signal htmx will actually deliver — the HX-Trigger response header (app:error).
 *
 * Why 200 + HX-Trigger: htmx 2.0.6's default responseHandling maps [45].. to swap:false, so a fragment
 * returned at a non-2xx is fetched then DISCARDED (never swapped). Returning it at 200 makes htmx swap
 * it; the real status rides the HX-Trigger header, which htmx processes regardless of status code.
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
    fun previewOverHtmx() = capture(http.postFormHtmx("/personas/compose", previewForm))

    @When("the owner previews a persona prompt without htmx and the model fails")
    fun previewWithoutHtmx() = capture(http.postForm("/personas/compose", previewForm))

    private fun capture(resp: ResponseEntity<String>) {
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        world.lastHxTrigger = resp.headers.getFirst("HX-Trigger")
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

    @Then("the response carries an htmx error trigger with status {int}")
    fun responseCarriesErrorTrigger(status: Int) {
        val trigger = world.lastHxTrigger
        assertNotNull(trigger, "expected an HX-Trigger header on the response, but there was none")
        // The advice emits {"app:error":{"status":<code>}} (status only — no message: HTTP header values
        // are ISO-8859-1 and the copy has non-Latin1 punctuation, so the client words the toast from the
        // status). htmx dispatches that as an app:error event whose detail carries the mapped status.
        assertTrue(
            trigger!!.contains("app:error"),
            "expected the HX-Trigger to carry an app:error event, got: $trigger",
        )
        assertTrue(
            trigger.contains("\"status\":$status"),
            "expected the HX-Trigger app:error to carry status $status, got: $trigger",
        )
    }

    @Then("the response carries no htmx error trigger")
    fun responseCarriesNoErrorTrigger() {
        assertNull(
            world.lastHxTrigger,
            "expected NO HX-Trigger header on a non-htmx error, but got: ${world.lastHxTrigger}",
        )
    }
}
