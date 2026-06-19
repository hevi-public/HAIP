package com.aiforum.acceptance.steps

import com.aiforum.acceptance.FailureMode
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.config.ScriptableLlmClient.Behavior
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import org.springframework.http.ResponseEntity

/**
 * Drives the generation endpoints over HTTP. The summon/fan-out/retry/cancel verbs all go through the
 * single LlmClient seam, whose behaviour the LLM-program steps script per scenario.
 */
class GenerationSteps(
    private val world: ScenarioWorld,
    private val llm: ScriptableLlmClient,
    private val http: HttpClient,
) {
    @Given("the LLM will fail with a {failureMode}")
    fun llmWillFail(mode: FailureMode) = llm.enqueue(Behavior.Fail(mode.makeException))

    @When("the owner summons {string}")
    fun summon(persona: String) {
        val resp = http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf(
                "personaIds" to listOf(persona),
                "text" to "what do you think?",
                "scope" to "WHOLE_THREAD",
                "includeSiblings" to false,
                "triggerMode" to "SUMMON",
            ),
        )
        capture(resp)
    }

    @When("the owner retries the reply")
    fun retry() = capture(http.post("/replies/${world.lastReplyId}/retry"))

    private fun capture(resp: ResponseEntity<String>) {
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        resp.body?.let { body ->
            Regex("data-reply-id=\"([^\"]+)\"").find(body)?.let { world.lastReplyId = it.groupValues[1] }
        }
    }
}
