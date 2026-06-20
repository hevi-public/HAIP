package com.aiforum.acceptance.steps

import com.aiforum.acceptance.FailureMode
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.config.ScriptableLlmClient.Behavior
import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import org.springframework.http.ResponseEntity

/**
 * Drives the generation endpoints over HTTP. Summon is async (§4): the POST returns a DRAFTING node, so
 * [summon] then polls [GenerationSettle] to the settled state — mirroring the browser's htmx poll — so
 * the existing `Then the reply is "posted"` (and the LlmClient spy) see the final outcome. The cancel
 * verbs leave the draft in flight (no poll) and trip the real token via the cancel endpoint.
 */
class GenerationSteps(
    private val world: ScenarioWorld,
    private val llm: ScriptableLlmClient,
    private val http: HttpClient,
    private val settle: GenerationSettle,
) {
    @Given("the LLM will fail with a {failureMode}")
    fun llmWillFail(mode: FailureMode) = llm.enqueue(Behavior.Fail(mode.makeException))

    @Given("the generation hangs until cancelled")
    fun generationHangs() = llm.enqueue(Behavior.HangUntilCancelled)

    @When("the owner summons {string}")
    fun summon(persona: String) {
        val resp = http.postJson("/threads/${world.threadId}/generate", summonBody(persona))
        world.lastStatus = resp.statusCode.value()
        val id = Html.allReplyIds(resp.body ?: "").firstOrNull() ?: error("summon returned no draft node")
        world.lastReplyId = id
        world.lastBody = settle.awaitSettled(id)
    }

    @When("the owner starts a draft from {string}")
    fun startDraft(persona: String) {
        // Leaves the draft in flight (no settle poll) so the next step can cancel it mid-generation.
        val resp = http.postJson("/threads/${world.threadId}/generate", summonBody(persona))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        world.lastReplyId = Html.allReplyIds(resp.body ?: "").firstOrNull() ?: error("starts a draft returned no draft node")
    }

    @When("the owner cancels the draft")
    fun cancelDraft() {
        // Trips the shared CancellationToken; the endpoint waits for the worker to settle to CANCELLED.
        val resp = http.post("/replies/${world.lastReplyId}/cancel")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @When("the owner retries the reply")
    fun retry() = capture(http.post("/replies/${world.lastReplyId}/retry"))

    private fun summonBody(persona: String) = mapOf(
        "personaIds" to listOf(persona),
        "text" to "what do you think?",
        "scope" to "WHOLE_THREAD",
        "includeSiblings" to false,
        "triggerMode" to "SUMMON",
    )

    private fun capture(resp: ResponseEntity<String>) {
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        resp.body?.let { body ->
            Regex("data-reply-id=\"([^\"]+)\"").find(body)?.let { world.lastReplyId = it.groupValues[1] }
        }
    }
}
