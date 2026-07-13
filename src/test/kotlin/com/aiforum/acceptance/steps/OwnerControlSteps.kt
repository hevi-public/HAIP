package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    @Then("the +1 button is present on {string}'s reply")
    fun plusOneButtonPresent(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNotNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected reply node for $persona in page",
        )
        assertTrue(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/plus-one\""),
            "expected +1 button (hx-post=/replies/$id/plus-one) in page:\n${world.lastBody}",
        )
    }

    @When("the owner deletes {string}'s reply")
    fun deleteReply(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        val resp = http.post("/replies/$id/delete")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the delete button is present on {string}'s reply")
    fun deleteButtonPresent(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNotNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected reply node for $persona in page",
        )
        assertTrue(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/delete\""),
            "expected delete button (hx-post=/replies/$id/delete) in page:\n${world.lastBody}",
        )
    }

    @Then("the thread no longer shows {string}'s reply")
    fun threadNoLongerShows(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected $persona's reply ($id) to be gone from the page:\n${world.lastBody}",
        )
    }

    @Then("the thread still shows {string}'s reply")
    fun threadStillShows(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNotNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected $persona's reply ($id) to still be on the page:\n${world.lastBody}",
        )
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
