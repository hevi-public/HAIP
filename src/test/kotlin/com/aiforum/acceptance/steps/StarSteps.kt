package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Star steps — the owner's branch-index bookmark. Mirrors the +1 steps: drive the toggle endpoint over
 * HTTP, then assert on the rail's stable hook (data-starred on data-branch-index-entry="<reply id>").
 */
class StarSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    // Precondition variant: star a reply without updating world.lastBody, so the scenario can
    // continue with a fresh page load as the assertion target (not the star-fragment response).
    @Given("the owner has starred {string}'s reply")
    fun ownerHasStarred(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        http.post("/replies/$id/star")
    }

    @When("the owner stars {string}'s reply")
    fun starReply(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        val resp = http.post("/replies/$id/star")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the star button is present on {string}'s reply")
    fun starButtonPresent(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNotNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected reply node for $persona in page",
        )
        assertTrue(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/star\""),
            "expected star button (hx-post=/replies/$id/star) in page:\n${world.lastBody}",
        )
    }

    @Then("the branch index entry for {string}'s reply is starred")
    fun branchEntryStarred(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertEquals(
            "true", Html.branchEntryAttr(world.lastBody ?: "", id, "data-starred"),
            "expected $persona's branch index entry to be starred in:\n${world.lastBody}",
        )
    }

    @Then("the branch index entry for {string}'s reply is not starred")
    fun branchEntryNotStarred(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertEquals(
            "false", Html.branchEntryAttr(world.lastBody ?: "", id, "data-starred"),
            "expected $persona's branch index entry NOT to be starred in:\n${world.lastBody}",
        )
    }
}
