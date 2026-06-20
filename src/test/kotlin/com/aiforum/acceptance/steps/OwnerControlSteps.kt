package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Owner-control steps, including the anti-sycophancy firewall (§7): assert the +1 is recorded and
 * shown to the owner, yet never appears in the PromptContext handed to the model (via the spy).
 */
class OwnerControlSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val llm: ScriptableLlmClient,
) {
    @When("the owner gives a +1 to {string}'s reply")
    fun plusOne(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        val resp = http.post("/replies/$id/plus-one")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the owner sees a vote count of {int} on {string}'s reply")
    fun seesVoteCount(count: Int, persona: String) {
        val id = world.replyIds["$persona's reply"]!!
        assertEquals(count.toString(), Html.replyAttr(world.lastBody ?: "", id, "data-vote-count"))
    }

    @Then("the model's context included {string}'s words {string}")
    fun contextIncluded(persona: String, words: String) {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        assertTrue(
            req.context.comments.any { it.body.contains(words) },
            "expected the model context to include \"$words\"",
        )
    }

    @Then("the model's context contained no vote signal")
    fun noVoteSignal() {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        val everything = buildString {
            append(req.context.personaSystemPrompt)
            req.context.comments.forEach { append(' ').append(it.authorId).append(' ').append(it.body) }
        }.lowercase()
        assertTrue(
            !everything.contains("+1") && !everything.contains("vote"),
            "the firewall leaked a vote signal into the model context: $everything",
        )
    }
}
