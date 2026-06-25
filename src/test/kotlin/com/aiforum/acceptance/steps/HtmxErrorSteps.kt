package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.ResponseEntity

/**
 * The honest-failure-UX acceptance steps (T1.4) — toast-only contract. The model failure itself is
 * enqueued by the existing `the LLM will fail with a {failureMode}` step (GenerationSteps) — the single
 * LlmClient seam — so these steps only drive the htmx-vs-not request and assert on the contract: a mapped
 * NON-2xx status with an empty body (htmx swaps nothing on a non-2xx, so nothing lands in the compose
 * field) plus the out-of-band failure signal — the HX-Trigger response header (app:error) the client
 * toasts off. There is no fragment any more.
 *
 * The surface is the persona prompt-compose PREVIEW (POST /personas/compose): its synchronous LLM call
 * (PromptComposer → llm.generate) is unguarded by design, so an enqueued failure escapes uncaught to
 * HtmxErrorAdvice — exactly the Whitelabel-in-the-textarea bug this task fixes.
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

    @Then("the response has no error fragment body")
    fun responseHasNoFragmentBody() {
        val body = world.lastBody ?: ""
        // The toast-only redesign returns an EMPTY htmx body (the failure rides the HX-Trigger header, not
        // a swapped body), so nothing lands in the compose field. (No data-error-fragment check — the hook
        // is gone, so asserting its absence would be vacuous; an empty body subsumes it.)
        assertTrue(
            body.isBlank(),
            "expected an empty htmx error body (nothing to swap), but got:\n$body",
        )
    }

    @Then("the response has a non-empty error body")
    fun responseHasNonEmptyBody() {
        // Non-htmx: the advice rethrows, so Boot renders its default error response (a real, non-empty
        // body) at the mapped status — NOT a swallowed 200/empty. Mirrors the htmx empty-body assertion.
        assertTrue(
            !(world.lastBody ?: "").isBlank(),
            "expected a non-empty error body (Boot's default handling), but it was blank",
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
