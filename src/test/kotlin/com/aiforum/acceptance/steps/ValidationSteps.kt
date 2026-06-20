package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.ResponseEntity

/**
 * Pre-generation validation (§4): empty question / no persona is rejected at the controller tier with
 * no LLM call. "No call" is asserted via the spy being empty.
 */
class ValidationSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val llm: ScriptableLlmClient,
) {
    @When("the owner submits a generation with empty text")
    fun emptyText() = capture(
        http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf("personaIds" to listOf("sol"), "text" to "", "triggerMode" to "SUMMON"),
        ),
    )

    @When("the owner submits a generation with no persona selected")
    fun noPersona() = capture(
        http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf("personaIds" to emptyList<String>(), "text" to "what do you think?", "triggerMode" to "SUMMON"),
        ),
    )

    @Then("no LLM call was made")
    fun noLlmCall() = assertTrue(llm.received.isEmpty(), "expected no LLM call, but spy saw ${llm.received.size}")

    private fun capture(resp: ResponseEntity<String>) {
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }
}
